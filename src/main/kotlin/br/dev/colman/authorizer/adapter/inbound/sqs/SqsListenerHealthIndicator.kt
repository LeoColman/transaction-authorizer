package br.dev.colman.authorizer.adapter.inbound.sqs

import io.awspring.cloud.sqs.listener.MessageListenerContainerRegistry
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.stereotype.Component

/**
 * Saúde do consumo da fila, que nenhum outro sinal cobria: sem isto, um listener
 * morto deixava `/actuator/health` verde e o único indício era a métrica de
 * contas registradas parar de crescer — algo que só se percebe olhando série
 * histórica, e tarde.
 *
 * Não entra no grupo `readiness` de propósito. Readiness responde "posso receber
 * tráfego HTTP", e autorizar transações não depende da fila: tirar a instância do
 * balanceador porque o consumo parou trocaria uma degradação parcial por
 * indisponibilidade total. O sinal serve para alarme, não para roteamento.
 */
@Component("sqsListener")
class SqsListenerHealthIndicator(
    private val registry: MessageListenerContainerRegistry,
) : HealthIndicator {

    override fun health(): Health {
        val containers = registry.listenerContainers
        val parados = containers.filterNot { it.isRunning }.map { it.id }

        return when {
            // Nenhum container registrado significa que o listener não subiu:
            // a aplicação está de pé e a fila não tem quem a consuma.
            containers.isEmpty() -> Health.down()
                .withDetail("motivo", "nenhum listener registrado")
                .build()

            parados.isNotEmpty() -> Health.down()
                .withDetail("motivo", "listener parado")
                .withDetail("containers", parados)
                .build()

            else -> Health.up()
                .withDetail("containers", containers.size)
                .build()
        }
    }
}
