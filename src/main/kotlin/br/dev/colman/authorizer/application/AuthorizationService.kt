package br.dev.colman.authorizer.application

import br.dev.colman.authorizer.application.port.inbound.AuthorizationResult
import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionCommand
import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionUseCase
import br.dev.colman.authorizer.application.port.outbound.TransactionRepository
import br.dev.colman.authorizer.domain.DuplicateTransactionException
import br.dev.colman.authorizer.domain.IdempotencyConflictException
import br.dev.colman.authorizer.domain.Transaction
import org.springframework.stereotype.Service

/**
 * Caso de uso de autorização com idempotência por transactionId (ADR-0002):
 * uma retentativa (mesmo id) recebe exatamente o resultado da autorização
 * original, sem alterar o saldo de novo. Replay só vale para payload idêntico;
 * mesmo id com payload divergente é conflito (409), nunca resposta silenciosa.
 */
@Service
class AuthorizationService(
    private val executor: AuthorizationExecutor,
    private val transactions: TransactionRepository,
) : AuthorizeTransactionUseCase {

    override fun authorize(command: AuthorizeTransactionCommand): AuthorizationResult {
        transactions.findById(command.transactionId)?.let {
            return replay(it, command)
        }

        return try {
            AuthorizationResult(executor.execute(command), replayed = false)
        } catch (@Suppress("SwallowedException") e: DuplicateTransactionException) {
            // Corrida entre requisições com o mesmo id: a outra venceu; devolve o resultado dela.
            val stored = transactions.findById(command.transactionId)
                ?: error("Transação ${command.transactionId} duplicada mas não encontrada")
            replay(stored, command)
        }
    }

    private fun replay(stored: Transaction, command: AuthorizeTransactionCommand): AuthorizationResult {
        if (!stored.matches(command)) throw IdempotencyConflictException(command.transactionId)
        return AuthorizationResult(stored, replayed = true)
    }

    private fun Transaction.matches(command: AuthorizeTransactionCommand): Boolean =
        accountId == command.accountId &&
            type == command.type &&
            amount.currency == command.amount.currency &&
            amount.normalized.compareTo(command.amount.normalized) == 0
}
