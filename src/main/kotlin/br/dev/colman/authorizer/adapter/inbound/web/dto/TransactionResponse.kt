package br.dev.colman.authorizer.adapter.inbound.web.dto

import br.dev.colman.authorizer.domain.Transaction
import br.dev.colman.authorizer.domain.TransactionStatus
import br.dev.colman.authorizer.domain.TransactionType
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * Resposta no formato definido pelo desafio. Observação: o desafio usa
 * "value" no valor da transação e "amount" no saldo da conta; o contrato
 * reproduz o exemplo fielmente.
 */
data class TransactionResponse(
    val transaction: TransactionPayload,
    val account: AccountPayload,
) {
    data class TransactionPayload(
        val id: UUID,
        val type: TransactionType,
        val amount: AmountPayload,
        val status: TransactionStatus,
        val timestamp: OffsetDateTime,
    )

    data class AmountPayload(
        val value: BigDecimal,
        val currency: String,
    )

    data class AccountPayload(
        val id: UUID,
        val balance: BalancePayload,
    )

    data class BalancePayload(
        val amount: BigDecimal,
        val currency: String,
    )

    companion object {
        fun from(transaction: Transaction, zone: ZoneId): TransactionResponse = TransactionResponse(
            transaction = TransactionPayload(
                id = transaction.id,
                type = transaction.type,
                amount = AmountPayload(
                    value = transaction.amount.normalized,
                    currency = transaction.amount.currency.name,
                ),
                status = transaction.status,
                timestamp = OffsetDateTime.ofInstant(transaction.timestamp, zone),
            ),
            account = AccountPayload(
                id = transaction.accountId,
                balance = BalancePayload(
                    amount = transaction.balanceAfter.normalized,
                    currency = transaction.balanceAfter.currency.name,
                ),
            ),
        )
    }
}
