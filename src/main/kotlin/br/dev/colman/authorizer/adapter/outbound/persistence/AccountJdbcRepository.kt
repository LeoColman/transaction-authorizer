package br.dev.colman.authorizer.adapter.outbound.persistence

import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.domain.Account
import br.dev.colman.authorizer.domain.AccountStatus
import br.dev.colman.authorizer.domain.Currency
import br.dev.colman.authorizer.domain.Money
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class AccountJdbcRepository(
    private val jdbc: JdbcClient,
    private val namedJdbc: NamedParameterJdbcTemplate,
) : AccountRepository {

    override fun insertAll(accounts: List<Account>): Int {
        if (accounts.isEmpty()) return 0
        val batch = accounts.map { account ->
            MapSqlParameterSource()
                .addValue("id", account.id)
                .addValue("ownerId", account.ownerId)
                .addValue("status", account.status.name)
                .addValue("balance", account.balance.normalized)
                .addValue("currency", account.balance.currency.name)
                .addValue("createdAt", OffsetDateTime.ofInstant(account.createdAt, ZoneOffset.UTC))
        }.toTypedArray()

        // A contagem por linha depende do modo padrão do driver: com
        // reWriteBatchedInserts=true o pgjdbc devolve SUCCESS_NO_INFO (-2) por
        // entrada e esta soma (e as métricas registered/duplicated) quebraria.
        // Não habilite essa flag na DB_URL sem revisar este método.
        return namedJdbc.batchUpdate(
            """
            INSERT INTO accounts (id, owner_id, status, balance, currency, created_at)
            VALUES (:id, :ownerId, :status, :balance, :currency, :createdAt)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
            batch,
        ).sum()
    }

    override fun findById(id: UUID): Account? = jdbc
        .sql("SELECT id, owner_id, status, balance, currency, created_at FROM accounts WHERE id = :id")
        .param("id", id)
        .query { rs, _ -> rs.toAccount() }
        .optional()
        .orElse(null)

    override fun applyCredit(accountId: UUID, amount: BigDecimal): BigDecimal? = jdbc
        .sql("UPDATE accounts SET balance = balance + :amount WHERE id = :id RETURNING balance")
        .param("amount", amount)
        .param("id", accountId)
        .query(BigDecimal::class.java)
        .optional()
        .orElse(null)

    override fun applyDebit(accountId: UUID, amount: BigDecimal): BigDecimal? = jdbc
        .sql(
            """
            UPDATE accounts SET balance = balance - :amount
            WHERE id = :id AND balance >= :amount
            RETURNING balance
            """.trimIndent(),
        )
        .param("amount", amount)
        .param("id", accountId)
        .query(BigDecimal::class.java)
        .optional()
        .orElse(null)

    override fun currentBalance(accountId: UUID): BigDecimal? = jdbc
        .sql("SELECT balance FROM accounts WHERE id = :id")
        .param("id", accountId)
        .query(BigDecimal::class.java)
        .optional()
        .orElse(null)

    private fun ResultSet.toAccount(): Account = Account(
        id = getObject("id", UUID::class.java),
        ownerId = getObject("owner_id", UUID::class.java),
        status = AccountStatus.valueOf(getString("status")),
        createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
        balance = Money(getBigDecimal("balance"), Currency.valueOf(getString("currency").trim())),
    )
}
