package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.domain.Account
import br.dev.colman.authorizer.domain.AccountStatus
import br.dev.colman.authorizer.domain.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Saturação de pool exercitada de verdade, sem mock do caso de uso: com uma única
 * conexão disponível e ela retida, a autorização falha ao adquirir conexão — seja
 * na leitura do fast-path, seja ao abrir a transação, que são exceções de
 * hierarquias diferentes do Spring. As duas precisam virar 503, e não 500.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // Pool mínimo que ainda deixa o contexto subir (Flyway precisa de conexão no
    // startup); o teste retém as duas para esgotá-lo.
    properties = [
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.connection-timeout=250",
    ],
)
class PoolExhaustionIT(
    private val dataSource: DataSource,
    private val accounts: AccountRepository,
) : FunSpec() {

    @LocalServerPort
    private var port: Int = 0

    init {
        test("pool esgotado responde 503 com Retry-After, sem gravar nada") {
            val accountId = UUID.randomUUID()
            accounts.insertAll(
                listOf(
                    Account(accountId, UUID.randomUUID(), AccountStatus.ENABLED, Instant.now(), Money.zero()),
                ),
            )

            val client = RestClient.builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }, { _, _ -> })
                .build()

            // Retém todo o pool durante a requisição.
            val response = dataSource.connection.use { _ ->
                dataSource.connection.use { _ ->
                    client.post()
                        .uri("/transactions/${UUID.randomUUID()}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""{"accountId":"$accountId","type":"CREDIT","amount":{"value":10.00,"currency":"BRL"}}""")
                        .retrieve()
                        .toEntity(String::class.java)
                }
            }

            response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
            response.headers.getFirst("Retry-After") shouldBe "1"
            response.body!! shouldContain "service-unavailable"
            accounts.currentBalance(accountId)!!.compareTo(java.math.BigDecimal.ZERO) shouldBe 0
        }
    }
}
