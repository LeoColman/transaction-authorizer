package br.dev.colman.authorizer.adapter.outbound.persistence

import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.domain.Account
import br.dev.colman.authorizer.domain.AccountStatus
import br.dev.colman.authorizer.domain.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
class AccountRepositoryIT(
    private val accounts: AccountRepository,
) : FunSpec() {

    private fun newAccount(balance: String = "0.00") = Account(
        id = UUID.randomUUID(),
        ownerId = UUID.randomUUID(),
        status = AccountStatus.ENABLED,
        createdAt = Instant.parse("2021-10-22T04:25:39Z"),
        balance = Money.brl(BigDecimal(balance)),
    )

    init {
        test("insere contas em lote e ignora duplicadas (idempotência de redelivery)") {
            val a = newAccount()
            val b = newAccount()

            accounts.insertAll(listOf(a, b)) shouldBe 2
            accounts.insertAll(listOf(a, b, newAccount())) shouldBe 1

            val stored = accounts.findById(a.id)
            stored.shouldNotBeNull()
            stored.ownerId shouldBe a.ownerId
            stored.status shouldBe AccountStatus.ENABLED
            stored.createdAt shouldBe a.createdAt
            stored.balance.value shouldBeEqualComparingTo BigDecimal.ZERO
        }

        test("insertAll com lista vazia retorna 0") {
            accounts.insertAll(emptyList()) shouldBe 0
        }

        test("crédito atômico soma e retorna o novo saldo") {
            val account = newAccount()
            accounts.insertAll(listOf(account))

            accounts.applyCredit(account.id, BigDecimal("10.50"))!! shouldBeEqualComparingTo BigDecimal("10.50")
            accounts.applyCredit(account.id, BigDecimal("0.50"))!! shouldBeEqualComparingTo BigDecimal("11.00")
        }

        test("crédito condicional nunca estoura o teto da coluna") {
            val account = newAccount()
            accounts.insertAll(listOf(account))
            accounts.applyCredit(account.id, BigDecimal("99999999999999990.00"))

            // Sem a condição de teto, o Postgres responderia "numeric field
            // overflow" (SQLState 22003) e a requisição viraria 500.
            accounts.applyCredit(account.id, BigDecimal("10.00")).shouldBeNull()
            accounts.currentBalance(account.id)!! shouldBeEqualComparingTo BigDecimal("99999999999999990.00")

            // O último centavo que ainda cabe é aceito: o teto é inclusivo.
            accounts.applyCredit(account.id, BigDecimal("9.99"))!! shouldBeEqualComparingTo Money.MAX
        }

        test("débito condicional nunca deixa saldo negativo") {
            val account = newAccount()
            accounts.insertAll(listOf(account))
            accounts.applyCredit(account.id, BigDecimal("50.00"))

            accounts.applyDebit(account.id, BigDecimal("50.01")).shouldBeNull()
            accounts.applyDebit(account.id, BigDecimal("50.00"))!! shouldBeEqualComparingTo BigDecimal.ZERO
            accounts.applyDebit(account.id, BigDecimal("0.01")).shouldBeNull()
        }

        test("operações em conta inexistente retornam null") {
            val ghost = UUID.randomUUID()
            accounts.applyCredit(ghost, BigDecimal.ONE).shouldBeNull()
            accounts.applyDebit(ghost, BigDecimal.ONE).shouldBeNull()
            accounts.currentBalance(ghost).shouldBeNull()
            accounts.findById(ghost).shouldBeNull()
        }

        test("50 débitos concorrentes contra saldo para apenas 10: exatamente 10 aprovados e saldo final zero") {
            val account = newAccount()
            accounts.insertAll(listOf(account))
            accounts.applyCredit(account.id, BigDecimal("100.00"))

            val approved = AtomicInteger()
            Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                val jobs = (1..50).map {
                    pool.submit {
                        if (accounts.applyDebit(account.id, BigDecimal("10.00")) != null) approved.incrementAndGet()
                    }
                }
                jobs.forEach { it.get() }
            }

            approved.get() shouldBe 10
            accounts.currentBalance(account.id)!! shouldBeEqualComparingTo BigDecimal.ZERO
        }

        test("créditos e débitos concorrentes misturados terminam com saldo consistente") {
            val account = newAccount()
            accounts.insertAll(listOf(account))

            val approvedDebits = AtomicInteger()
            Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                val credits = (1..30).map { pool.submit { accounts.applyCredit(account.id, BigDecimal("5.00")) } }
                val debits = (1..30).map {
                    pool.submit {
                        if (accounts.applyDebit(account.id, BigDecimal("7.00")) != null) approvedDebits.incrementAndGet()
                    }
                }
                (credits + debits).forEach { it.get() }
            }

            // 30 créditos de 5.00 = 150.00; cada débito aprovado tira 7.00.
            val expected = BigDecimal("150.00") - BigDecimal("7.00") * BigDecimal(approvedDebits.get())
            accounts.currentBalance(account.id)!! shouldBeEqualComparingTo expected
            (accounts.currentBalance(account.id)!! >= BigDecimal.ZERO) shouldBe true
        }
    }
}
