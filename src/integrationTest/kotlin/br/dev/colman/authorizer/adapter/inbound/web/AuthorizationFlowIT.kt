package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.domain.Account
import br.dev.colman.authorizer.domain.AccountStatus
import br.dev.colman.authorizer.domain.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationFlowIT(
    private val accounts: AccountRepository,
) : FunSpec() {

    @LocalServerPort
    private var port: Int = 0

    private val client: RestClient by lazy {
        RestClient.builder()
            .baseUrl("http://localhost:$port")
            .defaultStatusHandler({ true }, { _, _ -> })
            .build()
    }

    private fun createAccount(): UUID {
        val account = Account(
            id = UUID.randomUUID(),
            ownerId = UUID.randomUUID(),
            status = AccountStatus.ENABLED,
            createdAt = Instant.now(),
            balance = Money.zero(),
        )
        accounts.insertAll(listOf(account))
        return account.id
    }

    private fun post(transactionId: String, body: String): ResponseEntity<String> = client.post()
        .uri("/transactions/$transactionId")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String::class.java)

    private fun post(transactionId: UUID, body: String) = post(transactionId.toString(), body)

    private fun body(accountId: UUID, type: String, value: String, currency: String = "BRL") =
        """{"accountId":"$accountId","type":"$type","amount":{"value":$value,"currency":"$currency"}}"""

    init {
        test("crédito em conta nova é aprovado com 200 e resposta no contrato do desafio") {
            val accountId = createAccount()
            val transactionId = UUID.randomUUID()

            val response = post(transactionId, body(accountId, "CREDIT", "97.07"))

            response.statusCode shouldBe HttpStatus.OK
            response.body!! shouldContain """"id":"$transactionId""""
            response.body!! shouldContain """"type":"CREDIT""""
            response.body!! shouldContain """"value":97.07"""
            response.body!! shouldContain """"status":"SUCCEEDED""""
            response.body!! shouldContain """"account":{"id":"$accountId","balance":{"amount":97.07,"currency":"BRL"}}"""
            response.headers.getFirst("X-Idempotent-Replay") shouldBe "false"
        }

        test("débito sem saldo é recusado com 422, status FAILED e saldo intacto") {
            val accountId = createAccount()
            post(UUID.randomUUID(), body(accountId, "CREDIT", "30.00"))

            val response = post(UUID.randomUUID(), body(accountId, "DEBIT", "30.01"))

            response.statusCode shouldBe HttpStatus.UNPROCESSABLE_CONTENT
            response.body!! shouldContain """"status":"FAILED""""
            response.body!! shouldContain """"balance":{"amount":30.00,"currency":"BRL"}"""
            accounts.currentBalance(accountId)!! shouldBeEqualComparingTo BigDecimal("30.00")
        }

        test("débito do valor exato do saldo zera a conta") {
            val accountId = createAccount()
            post(UUID.randomUUID(), body(accountId, "CREDIT", "30.00"))

            val response = post(UUID.randomUUID(), body(accountId, "DEBIT", "30.00"))

            response.statusCode shouldBe HttpStatus.OK
            response.body!! shouldContain """"balance":{"amount":0.00,"currency":"BRL"}"""
        }

        test("retentativa com mesmo transactionId devolve o mesmo resultado e não debita duas vezes") {
            val accountId = createAccount()
            post(UUID.randomUUID(), body(accountId, "CREDIT", "100.00"))
            val transactionId = UUID.randomUUID()

            val first = post(transactionId, body(accountId, "DEBIT", "40.00"))
            val replay = post(transactionId, body(accountId, "DEBIT", "40.00"))

            first.statusCode shouldBe HttpStatus.OK
            replay.statusCode shouldBe HttpStatus.OK
            replay.headers.getFirst("X-Idempotent-Replay") shouldBe "true"
            replay.body shouldBe first.body
            accounts.currentBalance(accountId)!! shouldBeEqualComparingTo BigDecimal("60.00")
        }

        test("requisições concorrentes com o mesmo transactionId debitam uma única vez") {
            val accountId = createAccount()
            post(UUID.randomUUID(), body(accountId, "CREDIT", "100.00"))
            val transactionId = UUID.randomUUID()
            val request = body(accountId, "DEBIT", "10.00")

            val responses = Executors.newVirtualThreadPerTaskExecutor().use { pool ->
                (1..10).map { pool.submit<ResponseEntity<String>> { post(transactionId, request) } }
                    .map { it.get() }
            }

            responses.filter { it.statusCode == HttpStatus.OK } shouldHaveSize 10
            accounts.currentBalance(accountId)!! shouldBeEqualComparingTo BigDecimal("90.00")
        }

        test("conta inexistente responde 404 problem detail") {
            val response = post(UUID.randomUUID(), body(UUID.randomUUID(), "CREDIT", "10.00"))

            response.statusCode shouldBe HttpStatus.NOT_FOUND
            response.body!! shouldContain "Conta não encontrada"
        }

        test("moeda não suportada responde 422 problem detail") {
            val accountId = createAccount()
            val response = post(UUID.randomUUID(), body(accountId, "CREDIT", "10.00", currency = "USD"))

            response.statusCode shouldBe HttpStatus.UNPROCESSABLE_CONTENT
            response.body!! shouldContain "Moeda não suportada"
        }

        test("payload inválido responde 400: valor zero, negativo, mais de 2 casas, tipo desconhecido") {
            val accountId = createAccount()

            post(UUID.randomUUID(), body(accountId, "CREDIT", "0")).statusCode shouldBe HttpStatus.BAD_REQUEST
            post(UUID.randomUUID(), body(accountId, "CREDIT", "-5.00")).statusCode shouldBe HttpStatus.BAD_REQUEST
            post(UUID.randomUUID(), body(accountId, "CREDIT", "1.005")).statusCode shouldBe HttpStatus.BAD_REQUEST
            post(UUID.randomUUID(), body(accountId, "PIX", "10.00")).statusCode shouldBe HttpStatus.BAD_REQUEST
        }

        test("transactionId que não é UUID responde 400") {
            post("nao-e-uuid", body(UUID.randomUUID(), "CREDIT", "10.00")).statusCode shouldBe HttpStatus.BAD_REQUEST
        }
    }
}
