package br.dev.colman.authorizer.adapter.inbound.sqs

import br.dev.colman.authorizer.application.port.inbound.NewAccount
import br.dev.colman.authorizer.application.port.inbound.RegisterAccountsUseCase
import br.dev.colman.authorizer.domain.AccountStatus
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.readValue
import io.awspring.cloud.sqs.annotation.SqsListener
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.messaging.Message
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Consome a fila de abertura de contas (fila standard = entrega at-least-once,
 * possivelmente fora de ordem). O registro é idempotente (ON CONFLICT DO
 * NOTHING), então redelivery é inofensivo.
 *
 * Mensagens malformadas são registradas e descartadas individualmente para não
 * envenenar o lote (em produção iriam para uma DLQ, ver ADR-0005). Falha de
 * infraestrutura (ex.: banco fora) lança exceção: o lote inteiro volta para a
 * fila após o visibility timeout.
 */
@Component
class AccountCreatedListener(
    private val registerAccounts: RegisterAccountsUseCase,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
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
        log.error("Mensagem de abertura de conta malformada, descartando: {}", payload, e)
        null
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
        createdAt = Instant.ofEpochSecond(account.createdAt.toLong()),
    )
}
