package br.dev.colman.authorizer.application.port.inbound

import br.dev.colman.authorizer.domain.Money
import br.dev.colman.authorizer.domain.Transaction
import br.dev.colman.authorizer.domain.TransactionType
import java.util.UUID

data class AuthorizeTransactionCommand(
    val transactionId: UUID,
    val accountId: UUID,
    val type: TransactionType,
    val amount: Money,
) {
    init {
        require(amount.isPositive()) { "Valor da transação deve ser positivo: ${amount.value}" }
    }
}

/**
 * @param replayed true quando o resultado veio de uma autorização anterior com o
 * mesmo transactionId (resposta idempotente, nenhum saldo foi alterado agora).
 */
data class AuthorizationResult(
    val transaction: Transaction,
    val replayed: Boolean,
)

interface AuthorizeTransactionUseCase {
    fun authorize(command: AuthorizeTransactionCommand): AuthorizationResult
}
