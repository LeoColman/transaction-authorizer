package br.dev.colman.authorizer.adapter.inbound.sqs

import br.dev.colman.authorizer.TestInfra
import io.awspring.cloud.sqs.operations.SqsTemplate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.Message
import java.time.Duration
import java.util.UUID

/**
 * Mensagem malformada não pode voltar para a fila (falharia para sempre) nem
 * sumir em silêncio: sai do fluxo na primeira tentativa e fica na DLQ, onde dá
 * para inspecionar e reprocessar depois de corrigir a origem.
 */
@SpringBootTest
class DeadLetterQueueIT(
    private val sqsTemplate: SqsTemplate,
) : FunSpec() {

    /**
     * Procura uma mensagem específica na DLQ. Descarta as demais em vez de exigir
     * fila vazia: outros testes de integração também arquivam mensagens aqui, e
     * depender da ordem de execução deixaria a suíte instável.
     */
    private fun aguardarNaDlq(payloadEsperado: String): Message<String> {
        var encontrada: Message<String>? = null
        await.atMost(Duration.ofSeconds(30)) untilAsserted {
            if (encontrada == null) {
                val recebida = sqsTemplate.receive(TestInfra.DLQ_NAME, String::class.java).orElse(null)
                if (recebida?.payload == payloadEsperado) encontrada = recebida
            }
            encontrada.shouldNotBeNull()
        }
        return encontrada!!
    }

    init {
        test("mensagem malformada vai para a DLQ com o motivo anexado") {
            val payloadQuebrado =
                """{"account":{"id":"nao-e-uuid-${UUID.randomUUID()}","owner":"x","created_at":"1","status":"ENABLED"}}"""

            sqsTemplate.send(TestInfra.QUEUE_NAME, payloadQuebrado)

            val arquivada = aguardarNaDlq(payloadQuebrado)
            arquivada.headers["x-authorizer-motivo"].toString() shouldContain "nao-e-uuid"
        }

        test("created_at fora da faixa suportada também é arquivado, não reprocessado") {
            // Nanossegundos enviados como segundos: Instant aceita, o banco não.
            val payload =
                """{"account":{"id":"${UUID.randomUUID()}","owner":"${UUID.randomUUID()}",""" +
                    """"created_at":"1634874339000000000","status":"ENABLED"}}"""

            sqsTemplate.send(TestInfra.QUEUE_NAME, payload)

            aguardarNaDlq(payload).payload shouldBe payload
        }
    }
}
