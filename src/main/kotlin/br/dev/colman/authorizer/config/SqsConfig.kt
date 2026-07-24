package br.dev.colman.authorizer.config

import io.awspring.cloud.autoconfigure.sqs.SqsAsyncClientCustomizer
import io.awspring.cloud.sqs.config.SqsMessageListenerContainerFactory
import io.awspring.cloud.sqs.listener.ListenerMode
import io.awspring.cloud.sqs.listener.QueueNotFoundStrategy
import io.awspring.cloud.sqs.listener.acknowledgement.handler.AcknowledgementMode
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.core.retry.RetryMode
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import java.time.Duration

@Configuration
class SqsConfig {

    /**
     * Consumo em lote (10 mensagens por poll, limite do SQS) com ack apenas em
     * sucesso: se o processamento falhar, o lote volta para a fila após o
     * visibility timeout (retry natural do SQS, ver ADR-0005).
     *
     * queueNotFoundStrategy é FAIL por padrão: fila inexistente (nome errado,
     * infra não provisionada) derruba o startup em vez de criar silenciosamente
     * uma fila vazia e consumir do lugar errado para sempre. O perfil local
     * usa CREATE para conveniência com LocalStack (ver application.yml).
     */
    @Bean
    fun defaultSqsListenerContainerFactory(
        sqsAsyncClient: SqsAsyncClient,
        @Value("\${authorizer.sqs.max-concurrent-messages:100}") maxConcurrentMessages: Int,
        @Value("\${authorizer.sqs.queue-not-found-strategy:FAIL}") queueNotFoundStrategy: QueueNotFoundStrategy,
    ): SqsMessageListenerContainerFactory<Any> =
        SqsMessageListenerContainerFactory.builder<Any>()
            .sqsAsyncClient(sqsAsyncClient)
            .configure { options ->
                options
                    .listenerMode(ListenerMode.BATCH)
                    .maxMessagesPerPoll(10)
                    .maxConcurrentMessages(maxConcurrentMessages)
                    .pollTimeout(Duration.ofSeconds(10))
                    .acknowledgementMode(AcknowledgementMode.ON_SUCCESS)
                    .queueNotFoundStrategy(queueNotFoundStrategy)
            }
            .build()

    /**
     * Retry adaptativo do SDK AWS: backoff exponencial com full jitter e
     * client-side rate limiting em caso de throttling.
     */
    @Bean
    fun sqsAsyncClientCustomizer(): SqsAsyncClientCustomizer = SqsAsyncClientCustomizer { builder ->
        builder.overrideConfiguration { it.retryStrategy(RetryMode.ADAPTIVE_V2) }
    }
}
