package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionUseCase
import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.QueryTimeoutException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
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

    private fun autorizar() = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()
        .post()
        .uri("/transactions/${UUID.randomUUID()}")
        .contentType(MediaType.APPLICATION_JSON)
        .body("""{"accountId":"${UUID.randomUUID()}","type":"CREDIT","amount":{"value":1.00,"currency":"BRL"}}""")
        .retrieve()
        .toEntity(String::class.java)

    init {
        test("erro inesperado no caso de uso responde 500 problem detail sem vazar detalhes") {
            every { authorizeTransaction.authorize(any()) } throws IllegalStateException("segredo interno")

            val response = autorizar()

            response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            response.body!! shouldContain "internal-error"
            response.body!! shouldNotContain "segredo interno"
        }

        test("falha ao ABRIR a transação também responde 503, não 500") {
            // Saturação dentro de um @Transactional chega como
            // CannotCreateTransactionException, de hierarquia diferente da
            // DataAccessResourceFailureException do fast-path — é o caminho da
            // maioria do tráfego e precisa do mesmo tratamento.
            every { authorizeTransaction.authorize(any()) } throws
                CannotCreateTransactionException("Could not open JDBC Connection for transaction")

            val response = autorizar()

            response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
            response.headers.getFirst("Retry-After") shouldBe "1"
            response.body!! shouldContain "service-unavailable"
        }

        test("falha no commit responde 500 dizendo como descobrir o desfecho real") {
            // Resultado incerto: a transação pode ter sido efetivada antes de a
            // conexão morrer. Continua 500 (retentar às cegas moveria dinheiro
            // de novo), mas a orientação precisa ser o replay com o mesmo id —
            // "tente novamente" genérico levaria o cliente a gerar id novo e
            // duplicar a operação.
            every { authorizeTransaction.authorize(any()) } throws
                TransactionSystemException("Could not commit JDBC transaction")

            val response = autorizar()

            response.statusCode shouldBe HttpStatus.INTERNAL_SERVER_ERROR
            response.body!! shouldContain "uncertain-result"
            response.body!! shouldContain "mesmo transactionId"
        }

        test("conexão derrubada pelo banco responde 503, não 500") {
            // Failover de RDS, restart do servidor ou pg_terminate_backend deixam
            // o pool com conexões mortas; ao usá-las, o Spring traduz para
            // DataAccessResourceFailureException. É indisponibilidade transitória,
            // detectada antes de qualquer escrita — o cliente precisa saber que
            // pode retentar. O DatabaseRecoveryIT prova a recuperação de verdade,
            // mas não garante o status: lá o pool às vezes renova a conexão antes
            // da requisição chegar, e o 500 não chega a aparecer.
            every { authorizeTransaction.authorize(any()) } throws
                DataAccessResourceFailureException("This connection has been closed")

            val response = autorizar()

            response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
            response.headers.getFirst("Retry-After") shouldBe "1"
            response.body!! shouldContain "service-unavailable"
        }

        test("falha transitória DENTRO da transação também responde 503, não 500") {
            // Terceiro ramo do handler: timeout de consulta e deadlock chegam
            // como TransientDataAccessException, já com rollback feito antes do
            // commit. Sem este caso, remover o tipo da lista não quebraria a
            // suíte e a saturação voltaria a virar 500 em silêncio.
            every { authorizeTransaction.authorize(any()) } throws
                QueryTimeoutException("Consulta excedeu o tempo limite")

            val response = autorizar()

            response.statusCode shouldBe HttpStatus.SERVICE_UNAVAILABLE
            response.headers.getFirst("Retry-After") shouldBe "1"
            response.body!! shouldContain "service-unavailable"
        }
    }
}
