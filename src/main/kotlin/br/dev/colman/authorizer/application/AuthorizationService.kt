package br.dev.colman.authorizer.application

import br.dev.colman.authorizer.application.port.inbound.AuthorizationResult
import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionCommand
import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionUseCase
import br.dev.colman.authorizer.application.port.outbound.TransactionRepository
import br.dev.colman.authorizer.domain.DuplicateTransactionException
import org.springframework.stereotype.Service

/**
 * Caso de uso de autorização com idempotência por transactionId (ADR-0002):
 * uma retentativa (mesmo id) recebe exatamente o resultado da autorização
 * original, sem alterar o saldo de novo.
 */
@Service
class AuthorizationService(
    private val executor: AuthorizationExecutor,
    private val transactions: TransactionRepository,
) : AuthorizeTransactionUseCase {

    override fun authorize(command: AuthorizeTransactionCommand): AuthorizationResult {
        transactions.findById(command.transactionId)?.let {
            return AuthorizationResult(it, replayed = true)
        }

        return try {
            AuthorizationResult(executor.execute(command), replayed = false)
        } catch (e: DuplicateTransactionException) {
            // Corrida entre requisições com o mesmo id: a outra venceu; devolve o resultado dela.
            val stored = transactions.findById(command.transactionId)
                ?: error("Transação ${command.transactionId} duplicada mas não encontrada")
            AuthorizationResult(stored, replayed = true)
        }
    }
}
