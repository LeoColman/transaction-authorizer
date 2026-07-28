package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.domain.Account
import br.dev.colman.authorizer.domain.AccountStatus
import br.dev.colman.authorizer.domain.Money
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Caos automatizado: o banco derruba todas as conexões da aplicação no meio da
 * operação, como faria um failover de RDS, um restart ou um `pg_terminate_backend`
 * de um DBA. O serviço precisa se recuperar sozinho, sem reinício.
 *
 * Duas coisas são verificadas, e a segunda foi a que motivou o teste: além de
 * voltar a atender, o serviço não pode responder 500 no caminho. Conexão morta é
 * indisponibilidade transitória, detectada antes de qualquer escrita ser
 * confirmada — o cliente precisa receber 503 com `Retry-After`, que diz que
 * retentar é seguro, e não um erro que soa como defeito de aplicação.
 *
 * Depende de execução sequencial dos specs de integração (o padrão do Kotest
 * aqui): derrubar as conexões afeta o contexto compartilhado enquanto dura.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatabaseRecoveryIT(
    private val dataSource: DataSource,
    private val accounts: AccountRepository,
) : FunSpec() {

    @LocalServerPort
    private var port: Int = 0

    private fun derrubarConexoesDaAplicacao(): Int = dataSource.connection.use { conexao ->
        conexao.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT count(*) FROM (
                    SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                    WHERE datname = current_database() AND pid <> pg_backend_pid()
                ) encerradas
                """.trimIndent(),
            ).use {
                it.next()
                it.getInt(1)
            }
        }
    }

    init {
        test("banco derruba todas as conexões: serviço volta sozinho e nunca responde 500") {
            val accountId = UUID.randomUUID()
            accounts.insertAll(
                listOf(Account(accountId, UUID.randomUUID(), AccountStatus.ENABLED, Instant.now(), Money.zero())),
            )

            val client = RestClient.builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }, { _, _ -> })
                .build()

            fun creditarUmReal(): HttpStatus = client.post()
                .uri("/transactions/${UUID.randomUUID()}")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""{"accountId":"$accountId","type":"CREDIT","amount":{"value":1.00,"currency":"BRL"}}""")
                .retrieve()
                .toBodilessEntity()
                .statusCode as HttpStatus

            creditarUmReal() shouldBe HttpStatus.OK
            // Sem conexões derrubadas o teste não exercitaria nada.
            derrubarConexoesDaAplicacao() shouldBeGreaterThan 0

            // O pool devolve conexões mortas até renová-las. Tentamos algumas
            // vezes: o serviço tem que voltar sem ninguém reiniciar nada.
            val respostas = (1..5).map { creditarUmReal() }

            respostas shouldNotContain HttpStatus.INTERNAL_SERVER_ERROR
            respostas.last() shouldBe HttpStatus.OK

            // Só as autorizações bem-sucedidas moveram dinheiro: as que falharam
            // sofreram rollback antes de qualquer escrita.
            val aprovadas = 1 + respostas.count { it == HttpStatus.OK }
            accounts.currentBalance(accountId)!! shouldBe BigDecimal("$aprovadas.00")
        }
    }
}
