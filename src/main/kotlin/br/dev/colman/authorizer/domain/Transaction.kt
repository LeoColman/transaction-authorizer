package br.dev.colman.authorizer.domain

import java.time.Instant
import java.util.UUID

enum class TransactionType {
    CREDIT,
    DEBIT,
}

enum class TransactionStatus {
    /** Autorização APROVADA: o saldo foi alterado. */
    SUCCEEDED,

    /** Autorização RECUSADA: o saldo não foi alterado (ex.: saldo insuficiente). */
    FAILED,
}

/**
 * Resultado imutável de uma autorização. Uma vez decidida, a autorização de um
 * transactionId nunca muda: novas tentativas com o mesmo id recebem o mesmo
 * resultado (idempotência, ADR-0002).
 */
data class Transaction(
    val id: UUID,
    val accountId: UUID,
    val type: TransactionType,
    val amount: Money,
    val status: TransactionStatus,
    val balanceAfter: Money,
    val timestamp: Instant,
) {
    init {
        require(amount.isPositive()) { "Valor da transação deve ser positivo: ${amount.value}" }
        require(!balanceAfter.isNegative()) { "Saldo resultante não pode ser negativo: ${balanceAfter.value}" }
    }

    val approved: Boolean
        get() = status == TransactionStatus.SUCCEEDED
}
