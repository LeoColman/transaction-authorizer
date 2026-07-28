package br.dev.colman.authorizer.adapter.inbound.sqs

import io.awspring.cloud.sqs.listener.MessageListenerContainer
import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.boot.health.contributor.Status

class SqsListenerHealthIndicatorSpec : FunSpec({

    fun container(id: String, rodando: Boolean): MessageListenerContainer<*> = mockk {
        every { this@mockk.id } returns id
        every { isRunning } returns rodando
    }

    fun indicador(vararg containers: MessageListenerContainer<*>): SqsListenerHealthIndicator {
        val registry = mockk<MessageListenerContainerRegistry> {
            every { listenerContainers } returns containers.toList()
        }
        return SqsListenerHealthIndicator(registry)
    }

    test("todos os listeners rodando reportam UP") {
        val health = indicador(container("fila-principal", rodando = true)).health()

        health.status shouldBe Status.UP
        health.details["containers"] shouldBe 1
    }

    test("listener parado derruba a saúde e diz qual") {
        // O caso que motivou o indicador: a aplicação responde HTTP normalmente
        // enquanto a fila deixou de ser consumida.
        val health = indicador(
            container("fila-principal", rodando = false),
            container("outra-fila", rodando = true),
        ).health()

        health.status shouldBe Status.DOWN
        health.details["containers"] shouldBe listOf("fila-principal")
        health.details shouldContainKey "motivo"
    }

    test("nenhum listener registrado é falha, não saúde por vacuidade") {
        // Sem esta guarda, um listener que nem chegou a ser criado (nome de fila
        // errado, autoconfiguração desligada) passaria por saudável.
        val health = indicador().health()

        health.status shouldBe Status.DOWN
        health.details["motivo"] shouldBe "nenhum listener registrado"
    }
})
