package br.dev.colman.authorizer.adapter.inbound.sqs

import br.dev.colman.authorizer.TestInfra
import br.dev.colman.authorizer.application.port.inbound.RegisterAccountsUseCase
import com.ninjasquad.springmockk.MockkBean
import io.awspring.cloud.sqs.operations.SqsTemplate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.QueryTimeoutException
import java.time.Duration
import java.util.UUID

/**
 * O outro caminho para a DLQ, complementar ao de mensagem malformada: aqui a
 * mensagem é válida e o que falha é a infraestrutura. O lote volta para a fila,
 * e depois de `maxReceiveCount` tentativas o próprio SQS a move — sem a redrive
 * policy na fila ela circularia para sempre, consumindo poll e log sem fim.
 *
 * Fila dedicada com visibility de 1s: na fila real (30s) o teste levaria minutos.
 */
@SpringBootTest(properties = ["authorizer.sqs.account-created-queue=\${TEST_FALHA_QUEUE:fila-que-sempre-falha}"])
class RedriveToDlqIT(
    private val sqsTemplate: SqsTemplate,
) : FunSpec() {

    @MockkBean
    private lateinit var registerAccounts: RegisterAccountsUseCase

    init {
        test("mensagem válida que falha sempre acaba na DLQ pelo redrive do SQS") {
            every { registerAccounts.register(any()) } throws QueryTimeoutException("banco indisponível")

            val accountId = UUID.randomUUID()
            val payload =
                """{"account":{"id":"$accountId","owner":"${UUID.randomUUID()}",""" +
                    """"created_at":"1634874339","status":"ENABLED"}}"""

            sqsTemplate.send(TestInfra.FAILING_QUEUE_NAME, payload)

            // maxReceiveCount = 2 e visibility de 1s: a mensagem é entregue,
            // falha, volta, é entregue de novo e só então o SQS a redireciona.
            await.atMost(Duration.ofSeconds(60)) untilAsserted {
                val arquivada = sqsTemplate.receive(TestInfra.FAILING_DLQ_NAME, String::class.java).orElse(null)
                arquivada.shouldNotBeNull()
                // A mensagem que chega é a original, não uma cópia do listener:
                // este caminho é do SQS, e a conta nunca chegou a ser registrada.
                arquivada.payload shouldBe payload
                arquivada.headers.containsKey("x-authorizer-motivo") shouldBe false
            }
            // Chegou na DLQ depois de ser tentada de novo, não de primeira.
            verify(atLeast = TestInfra.MAX_RECEIVE_COUNT) { registerAccounts.register(any()) }
        }
    }
}
