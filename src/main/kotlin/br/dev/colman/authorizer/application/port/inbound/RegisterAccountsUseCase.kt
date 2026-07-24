package br.dev.colman.authorizer.application.port.inbound

import br.dev.colman.authorizer.domain.AccountStatus
import java.time.Instant
import java.util.UUID

data class NewAccount(
    val id: UUID,
    val ownerId: UUID,
    val status: AccountStatus,
    val createdAt: Instant,
)

interface RegisterAccountsUseCase {
    /**
     * Registra contas informadas pelo sistema de abertura de contas.
     * Idempotente: contas já registradas são ignoradas (redelivery de fila standard).
     *
     * @return quantidade de contas efetivamente novas.
     */
    fun register(accounts: List<NewAccount>): Int
}
