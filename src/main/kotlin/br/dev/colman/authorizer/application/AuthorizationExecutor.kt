package br.dev.colman.authorizer.application

import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionCommand
import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.application.port.outbound.TransactionRepository
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

/**
 * Executa uma autorização dentro de uma única transação de banco:
 * alteração de saldo e registro do resultado são atômicos.
 *
 * Concorrência (ADR-0002):
 * - O saldo é alterado por UPDATE condicional atômico; nunca há lost update
 *   nem saldo negativo, mesmo com N instâncias concorrentes.
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

    @Transactional
    fun execute(command: AuthorizeTransactionCommand): Transaction {
        val account = accounts.findById(command.accountId)
            ?: throw AccountNotFoundException(command.accountId)
        if (!account.isEnabled()) throw AccountDisabledException(account.id)

        val newBalance = when (command.type) {
            TransactionType.CREDIT -> accounts.applyCredit(account.id, command.amount.normalized)
            TransactionType.DEBIT -> accounts.applyDebit(account.id, command.amount.normalized)
        }

        val transaction = if (newBalance != null) {
            authorized(command, TransactionStatus.SUCCEEDED, Money(newBalance, command.amount.currency))
        } else {
            // Débito recusado por saldo insuficiente: relê o saldo para responder o
            // valor mais recente (a leitura inicial pode ter ficado defasada).
            val balance = accounts.currentBalance(account.id)
                ?: throw AccountNotFoundException(account.id)
            authorized(command, TransactionStatus.FAILED, Money(balance, command.amount.currency))
        }

        transactions.insert(transaction)
        return transaction
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
}
