package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionUseCase
import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.CannotCreateTransactionException
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

        test("falha ao ABRIR a transação também responde 503, não 500") {
            // Saturação dentro de um @Transactional chega como
            // CannotCreateTransactionException, de hierarquia diferente da
            // CannotGetJdbcConnectionException do fast-path — é o caminho da
            // maioria do tráfego e precisa do mesmo tratamento.
            every { authorizeTransaction.authorize(any()) } throws
                CannotCreateTransactionException("Could not open JDBC Connection for transaction")

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
