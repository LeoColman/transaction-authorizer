package br.dev.colman.authorizer.application

import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionCommand
import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.application.port.outbound.TransactionRepository
import br.dev.colman.authorizer.domain.Account
import br.dev.colman.authorizer.domain.AccountDisabledException
import br.dev.colman.authorizer.domain.AccountNotFoundException
import br.dev.colman.authorizer.domain.Money
import br.dev.colman.authorizer.domain.Transaction
import br.dev.colman.authorizer.domain.TransactionStatus
import br.dev.colman.authorizer.domain.TransactionType
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Executa uma autorização dentro de uma única transação de banco:
 * alteração de saldo e registro do resultado são atômicos.
 *
 * Concorrência (ADR-0002):
 * - O saldo é alterado por UPDATE condicional atômico; nunca há lost update,
 *   saldo negativo nem saldo fora da faixa suportada, mesmo com N instâncias
 *   concorrentes. Zero linhas afetadas é recusa de negócio, não falha.
 * - Se duas requisições com o mesmo transactionId passarem juntas pelo
 *   fast-path de replay, a segunda viola a PK de transactions ao inserir e a
 *   transação de banco inteira sofre rollback, desfazendo a dupla aplicação
 *   do saldo. O chamador trata a violação devolvendo o resultado já gravado.
 */
@Component
class AuthorizationExecutor(
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
    private val clock: Clock,
) {

    /**
     * O `timeout` limita a EXECUÇÃO, coisa que nenhuma outra camada faz: o
     * `connection-timeout` do Hikari limita só a espera por uma conexão do pool.
     * Sem ele, uma linha travada por outra sessão prende a requisição por tempo
     * indeterminado — e o desfecho é o pior possível: o chamador estoura o
     * próprio timeout e desiste, a transação conclui depois em silêncio, e a
     * retentativa (com id novo, porque o cliente nunca soube o resultado) move o
     * dinheiro de novo. A idempotência não cobre isso: ela protege o mesmo
     * `transactionId`, não uma segunda tentativa legítima do cliente.
     *
     * Com o teto, a espera vira erro transitório antes de qualquer escrita, e o
     * handler responde 503 com `Retry-After` — aí sim retentar é seguro. 3s é
     * cerca de 80x o p99 medido em carga (38ms a 7.4k req/s).
     */
    @Transactional(timeout = TIMEOUT_SECONDS)
    fun execute(command: AuthorizeTransactionCommand): Transaction {
        val account = loadEnabledAccount(command.accountId)

        val newBalance = when (command.type) {
            TransactionType.CREDIT -> accounts.applyCredit(account.id, command.amount.normalized)
            TransactionType.DEBIT -> accounts.applyDebit(account.id, command.amount.normalized)
        }

        val transaction = if (newBalance != null) {
            authorized(command, TransactionStatus.SUCCEEDED, Money(newBalance, command.amount.currency))
        } else {
            // Recusado pelo UPDATE condicional (débito sem saldo, ou crédito que
            // excederia o teto de saldo): relê o saldo para responder o valor mais
            // recente (a leitura inicial pode ter ficado defasada).
            val balance = accounts.currentBalance(account.id)
                ?: throw AccountNotFoundException(account.id)
            authorized(command, TransactionStatus.FAILED, Money(balance, command.amount.currency))
        }

        transactions.insert(transaction)
        return transaction
    }

    private fun loadEnabledAccount(accountId: UUID): Account {
        val account = accounts.findById(accountId) ?: throw AccountNotFoundException(accountId)
        if (!account.isEnabled()) throw AccountDisabledException(account.id)
        return account
    }

    private fun authorized(
        command: AuthorizeTransactionCommand,
        status: TransactionStatus,
        balanceAfter: Money,
    ): Transaction = Transaction(
        id = command.transactionId,
        accountId = command.accountId,
        type = command.type,
        amount = command.amount,
        status = status,
        balanceAfter = balanceAfter,
        // Trunca em microssegundos: precisão máxima do timestamptz do Postgres.
        // Garante que o replay idempotente devolva timestamp idêntico ao original.
        timestamp = clock.instant().truncatedTo(ChronoUnit.MICROS),
    )

    private companion object {
        const val TIMEOUT_SECONDS = 3
    }
}
