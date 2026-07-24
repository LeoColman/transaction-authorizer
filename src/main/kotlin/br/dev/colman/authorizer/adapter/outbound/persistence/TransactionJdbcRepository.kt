package br.dev.colman.authorizer.adapter.outbound.persistence

import br.dev.colman.authorizer.application.port.outbound.TransactionRepository
import br.dev.colman.authorizer.domain.Currency
import br.dev.colman.authorizer.domain.DuplicateTransactionException
import br.dev.colman.authorizer.domain.Money
import br.dev.colman.authorizer.domain.Transaction
import br.dev.colman.authorizer.domain.TransactionStatus
import br.dev.colman.authorizer.domain.TransactionType
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class TransactionJdbcRepository(
    private val jdbc: JdbcClient,
) : TransactionRepository {

    override fun findById(id: UUID): Transaction? = jdbc
        .sql(
            """
            SELECT id, account_id, type, amount, currency, status, balance_after, created_at
            FROM transactions WHERE id = :id
            """.trimIndent(),
        )
        .param("id", id)
        .query { rs, _ -> rs.toTransaction() }
        .optional()
        .orElse(null)

    override fun insert(transaction: Transaction) {
        try {
            jdbc
                .sql(
                    """
                    INSERT INTO transactions (id, account_id, type, amount, currency, status, balance_after, created_at)
                    VALUES (:id, :accountId, :type, :amount, :currency, :status, :balanceAfter, :createdAt)
                    """.trimIndent(),
                )
                .param("id", transaction.id)
                .param("accountId", transaction.accountId)
                .param("type", transaction.type.name)
                .param("amount", transaction.amount.normalized)
                .param("currency", transaction.amount.currency.name)
                .param("status", transaction.status.name)
                .param("balanceAfter", transaction.balanceAfter.normalized)
                .param("createdAt", OffsetDateTime.ofInstant(transaction.timestamp, ZoneOffset.UTC))
                .update()
        } catch (e: DuplicateKeyException) {
            throw DuplicateTransactionException(transaction.id)
        }
    }

    private fun ResultSet.toTransaction(): Transaction {
        val currency = Currency.valueOf(getString("currency").trim())
        return Transaction(
            id = getObject("id", UUID::class.java),
            accountId = getObject("account_id", UUID::class.java),
            type = TransactionType.valueOf(getString("type")),
            amount = Money(getBigDecimal("amount"), currency),
            status = TransactionStatus.valueOf(getString("status")),
            balanceAfter = Money(getBigDecimal("balance_after"), currency),
            timestamp = getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )
    }
}
