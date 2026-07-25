package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionUseCase
import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.util.UUID

/**
 * Falha inesperada no caso de uso não pode vazar stacktrace nem detalhe
 * interno: responde 500 com Problem Details genérico (e loga no servidor).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InternalErrorIT : FunSpec() {

    @MockkBean
    private lateinit var authorizeTransaction: AuthorizeTransactionUseCase

    @LocalServerPort
    private var port: Int = 0

    init {
        test("erro inesperado no caso de uso responde 500 problem detail sem vazar detalhes") {
            every { authorizeTransaction.authorize(any()) } throws IllegalStateException("segredo interno")

            val response = RestClient.builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }, { _, _ -> })
                .build()
                .post()
                .uri("/transactions/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"accountId":"${UUID.randomUUID()}","type":"CREDIT","amount":{"value":1.00,"currency":"BRL"}}""")
                .retrieve()
                .toEntity(String::class.java)

            response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            response.body!! shouldContain "internal-error"
            response.body!! shouldNotContain "segredo interno"
        }

        test("pool de conexões esgotado responde 503 com Retry-After, não 500") {
            // Saturação é transitória: 500 misturaria falta de capacidade com
            // defeito, tanto para o cliente quanto para o alarme de 5xx.
            every { authorizeTransaction.authorize(any()) } throws
                CannotGetJdbcConnectionException("Connection is not available, request timed out")

            val response = RestClient.builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }, { _, _ -> })
                .build()
                .post()
                .uri("/transactions/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"accountId":"${UUID.randomUUID()}","type":"CREDIT","amount":{"value":1.00,"currency":"BRL"}}""")
                .retrieve()
                .toEntity(String::class.java)

            response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
            response.headers.getFirst("Retry-After") shouldBe "1"
            response.body!! shouldContain "service-unavailable"
        }
    }
}
