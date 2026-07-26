package br.dev.colman.authorizer.adapter.inbound.sqs

import br.dev.colman.authorizer.application.port.inbound.NewAccount
import br.dev.colman.authorizer.application.port.inbound.RegisterAccountsUseCase
import br.dev.colman.authorizer.domain.AccountStatus
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import io.awspring.cloud.sqs.operations.SqsTemplate
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.messaging.Message
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Consome a fila de abertura de contas (fila standard = entrega at-least-once,
 * possivelmente fora de ordem). O registro é idempotente (ON CONFLICT DO
 * NOTHING), então redelivery é inofensivo.
 *
 * As duas formas de falha têm destinos diferentes, ambos terminando na DLQ:
 * mensagem malformada é copiada para lá e retirada do lote na primeira tentativa
 * (reprocessá-la daria o mesmo erro para sempre e envenenaria o lote inteiro);
 * falha de infraestrutura (ex.: banco fora) lança exceção e devolve o lote à
 * fila, e só depois de `maxReceiveCount` tentativas o próprio SQS move a
 * mensagem para a DLQ. Ver ADR-0005.
 */
@Component
class AccountCreatedListener(
    private val registerAccounts: RegisterAccountsUseCase,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val sqsTemplate: SqsTemplate,
    @Value("\${authorizer.sqs.dead-letter-queue}") private val deadLetterQueue: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @SqsListener("\${authorizer.sqs.account-created-queue}")
    fun onMessages(messages: List<Message<String>>) {
        val accounts = messages.mapNotNull { parse(it.payload) }
        val inserted = registerAccounts.register(accounts)

        meterRegistry.counter("authorizer.accounts.registered").increment(inserted.toDouble())
        meterRegistry.counter("authorizer.accounts.duplicated").increment((accounts.size - inserted).toDouble())
        log.debug("Lote de contas processado: {} mensagens, {} novas", messages.size, inserted)
    }

    private fun parse(payload: String): NewAccount? = runCatching {
        val message = objectMapper.readValue<AccountCreatedMessage>(payload)
        message.toNewAccount()
    }.getOrElse { e ->
        meterRegistry.counter("authorizer.sqs.messages.invalid").increment()
        // Só o motivo: o payload traz o identificador do titular, e a cópia
        // íntegra dele já vai para a DLQ, que é o canal com acesso restrito.
        // Repeti-lo aqui espalharia dado pessoal para o agregador de logs, que
        // na arquitetura proposta tem retenção e alcance bem maiores.
        log.error("Mensagem de abertura de conta malformada, enviando para a DLQ: {}", e.message)
        sendToDeadLetterQueue(payload, e)
        null
    }

    /**
     * Falha ao arquivar não pode derrubar o lote: as mensagens boas que vieram
     * junto seriam reprocessadas por causa de uma que já se sabe irrecuperável.
     * Perder a cópia é ruim, perder o lote é pior — e a métrica de inválidas já
     * foi contada de qualquer forma.
     *
     * Aqui, e só aqui, o payload vai para o log: a DLQ era a única cópia e ela
     * falhou, então o log é o último lugar onde a mensagem ainda pode ser
     * recuperada. Vaza dado pessoal, mas perder a mensagem inteira é pior.
     */
    private fun sendToDeadLetterQueue(payload: String, causa: Throwable) {
        runCatching {
            sqsTemplate.send { to -> to.queue(deadLetterQueue).payload(payload).header(MOTIVO, "${causa.message}") }
        }.onFailure {
            // Métrica própria: "malformada" e "malformada E perdida" são eventos
            // muito diferentes, e sem separá-los um alarme sobre mensagens
            // inválidas não distingue arquivamento normal de perda definitiva.
            meterRegistry.counter("authorizer.sqs.dead-letter.failures").increment()
            log.error("Falha ao enviar para a DLQ {}, mensagem preservada no log: {}", deadLetterQueue, payload, it)
        }
    }

    private companion object {
        const val MOTIVO = "x-authorizer-motivo"
    }
}

data class AccountCreatedMessage(val account: Payload) {

    data class Payload(
        val id: UUID,
        val owner: UUID,
        @JsonProperty("created_at") val createdAt: String,
        val status: String,
    )

    fun toNewAccount(): NewAccount = NewAccount(
        id = account.id,
        ownerId = account.owner,
        status = AccountStatus.valueOf(account.status),
        createdAt = createdAtInstant(),
    )

    /**
     * A faixa é validada AQUI, dentro do runCatching do parse. Um timestamp
     * absurdo (ex.: nanossegundos enviados como segundos) é aceito por
     * Instant.ofEpochSecond mas estoura na conversão para OffsetDateTime feita
     * só na escrita: seria uma falha de "infraestrutura", o lote inteiro voltaria
     * para a fila e a mesma mensagem falharia para sempre, sem nunca contar como
     * inválida. Validando no parse, ela é descartada individualmente com métrica,
     * como o ADR-0005 define para mensagem malformada.
     */
    private fun createdAtInstant(): Instant {
        val instant = Instant.ofEpochSecond(account.createdAt.toLong())
        require(instant in MIN_CREATED_AT..MAX_CREATED_AT) {
            "created_at fora da faixa suportada: ${account.createdAt}"
        }
        return instant
    }

    private companion object {
        val MIN_CREATED_AT: Instant = OffsetDateTime.parse("0001-01-01T00:00:00Z").toInstant()
        val MAX_CREATED_AT: Instant = OffsetDateTime.parse("9999-12-31T23:59:59Z").toInstant()
    }
}
