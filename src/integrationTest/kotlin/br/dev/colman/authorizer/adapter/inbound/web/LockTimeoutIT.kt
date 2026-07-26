package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.domain.Account
import br.dev.colman.authorizer.domain.AccountStatus
import br.dev.colman.authorizer.domain.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Complementa `PoolExhaustionIT`: lá a conexão nunca é adquirida; aqui ela é
 * adquirida normalmente e a QUERY é que fica presa atrás do lock de outra sessão.
 * Sem teto de execução a requisição espera para sempre — o cliente desiste e a
 * transação conclui sozinha depois, movendo dinheiro que ninguém mais está
 * esperando.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LockTimeoutIT(
    private val dataSource: DataSource,
    private val accounts: AccountRepository,
) : FunSpec() {

    @LocalServerPort
    private var port: Int = 0

    init {
        test("linha travada por outra sessão vira 503 dentro do teto, sem aplicar o saldo") {
            val accountId = UUID.randomUUID()
            accounts.insertAll(
                listOf(Account(accountId, UUID.randomUUID(), AccountStatus.ENABLED, Instant.now(), Money.zero())),
            )
            accounts.applyCredit(accountId, BigDecimal("100.00"))

            // Teto de leitura no cliente: sem ele, remover o timeout da transação
            // faria este teste travar para sempre (o lock só é solto depois da
            // resposta) em vez de acusar a regressão.
            val client = RestClient.builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }, { _, _ -> })
                .requestFactory(
                    SimpleClientHttpRequestFactory().apply {
                        setReadTimeout(Duration.ofSeconds(20))
                        setConnectTimeout(Duration.ofSeconds(5))
                    },
                )
                .build()

            val inicio = Instant.now()
            val response = dataSource.connection.use { conexao ->
                conexao.autoCommit = false
                // Segura o lock da linha por mais tempo que o teto da autorização.
                conexao.prepareStatement("SELECT balance FROM accounts WHERE id = ? FOR UPDATE").use { st ->
                    st.setObject(1, accountId)
                    st.executeQuery().use { it.next() }
                }

                client.post()
                    .uri("/transactions/${UUID.randomUUID()}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"accountId":"$accountId","type":"DEBIT","amount":{"value":10.00,"currency":"BRL"}}""")
                    .retrieve()
                    .toEntity(String::class.java)
                    .also { conexao.rollback() }
            }
            val decorrido = Duration.between(inicio, Instant.now())

            response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
            response.headers.getFirst("Retry-After") shouldBe "1"
            response.body!! shouldContain "service-unavailable"

            // Respondeu por causa do teto, não porque o lock foi liberado antes.
            // A margem cobre o `lock_timeout` de 1s mais o custo do contexto; se
            // fosse o teto de transação (3s) ou nada, passaria bem disso.
            decorrido shouldBeLessThan Duration.ofSeconds(3)
            // Rollback antes de qualquer escrita: retentar é seguro, e é o que o 503 promete.
            accounts.currentBalance(accountId)!! shouldBe BigDecimal("100.00")
        }
    }
}
