package br.dev.colman.authorizer.domain

import java.time.Instant
import java.util.UUID

enum class AccountStatus {
    ENABLED,
    DISABLED,
}

/**
 * Conta bancária informada pelo sistema externo de abertura de contas.
 * O saldo inicial de toda conta nova é ZERO (regra do desafio).
 */
data class Account(
    val id: UUID,
    val ownerId: UUID,
    val status: AccountStatus,
    val createdAt: Instant,
    val balance: Money,
) {
    fun isEnabled(): Boolean = status == AccountStatus.ENABLED

    companion object {
        fun opened(id: UUID, ownerId: UUID, status: AccountStatus, createdAt: Instant): Account =
            Account(id, ownerId, status, createdAt, Money.zero())
    }
}
