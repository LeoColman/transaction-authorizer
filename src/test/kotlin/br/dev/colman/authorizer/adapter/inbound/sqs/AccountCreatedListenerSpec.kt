package br.dev.colman.authorizer.adapter.inbound.sqs

import br.dev.colman.authorizer.application.port.inbound.NewAccount
import br.dev.colman.authorizer.application.port.inbound.RegisterAccountsUseCase
import br.dev.colman.authorizer.domain.AccountStatus
import tools.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.springframework.messaging.support.MessageBuilder
import java.time.Instant
import java.util.UUID

class AccountCreatedListenerSpec : FunSpec({

    val objectMapper = jacksonObjectMapper()

    fun payload(id: UUID = UUID.randomUUID(), owner: UUID = UUID.randomUUID(), createdAt: Long = 1634874339L) =
        """{"account":{"id":"$id","owner":"$owner","created_at":"$createdAt","status":"ENABLED"}}"""

    lateinit var registerAccounts: RegisterAccountsUseCase
    lateinit var meterRegistry: SimpleMeterRegistry
    lateinit var listener: AccountCreatedListener

    beforeTest {
        registerAccounts = mockk()
        meterRegistry = SimpleMeterRegistry()
        listener = AccountCreatedListener(registerAccounts, objectMapper, meterRegistry)
    }

    test("converte o payload da fila em NewAccount (created_at em epoch-seconds)") {
        val id = UUID.randomUUID()
        val owner = UUID.randomUUID()
        val captured = slot<List<NewAccount>>()
        every { registerAccounts.register(capture(captured)) } returns 1

        listener.onMessages(listOf(MessageBuilder.withPayload(payload(id, owner)).build()))

        val account = captured.captured.single()
        account.id shouldBe id
        account.ownerId shouldBe owner
        account.status shouldBe AccountStatus.ENABLED
        account.createdAt shouldBe Instant.ofEpochSecond(1634874339L)
    }

    test("processa lote inteiro e registra métricas de novas e duplicadas") {
        every { registerAccounts.register(any()) } returns 2

        listener.onMessages(listOf(payload(), payload(), payload()).map { MessageBuilder.withPayload(it).build() })

        meterRegistry.counter("authorizer.accounts.registered").count() shouldBe 2.0
        meterRegistry.counter("authorizer.accounts.duplicated").count() shouldBe 1.0
    }

    test("mensagem malformada é descartada sem envenenar o lote") {
        val captured = slot<List<NewAccount>>()
        every { registerAccounts.register(capture(captured)) } returns 1

        val messages = listOf(payload(), """{"account":{"id":"nao-e-uuid"}}""", "isso nem é json")
            .map { MessageBuilder.withPayload(it).build() }

        listener.onMessages(messages)

        captured.captured.size shouldBe 1
        meterRegistry.counter("authorizer.sqs.messages.invalid").count() shouldBe 2.0
    }

    test("status desconhecido é tratado como mensagem inválida (parse estrito)") {
        val captured = slot<List<NewAccount>>()
        every { registerAccounts.register(capture(captured)) } returns 0

        val weird = """{"account":{"id":"${UUID.randomUUID()}","owner":"${UUID.randomUUID()}","created_at":"123","status":"BANANA"}}"""
        listener.onMessages(listOf(MessageBuilder.withPayload(weird).build()))

        captured.captured.size shouldBe 0
        meterRegistry.counter("authorizer.sqs.messages.invalid").count() shouldBe 1.0
        verify(exactly = 1) { registerAccounts.register(emptyList()) }
    }
})
