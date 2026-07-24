package br.dev.colman.authorizer.adapter.inbound.sqs

import br.dev.colman.authorizer.TestInfra
import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import io.awspring.cloud.sqs.operations.SqsTemplate
import io.kotest.core.spec.style.FunSpec
import io.micrometer.core.instrument.MeterRegistry
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.boot.test.context.SpringBootTest
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

@SpringBootTest
class AccountCreatedListenerIT(
    private val sqsTemplate: SqsTemplate,
    private val accounts: AccountRepository,
    private val meterRegistry: MeterRegistry,
) : FunSpec() {

    private fun accountPayload(id: UUID, owner: UUID = UUID.randomUUID(), createdAt: Long = 1634874339L) =
        """{"account":{"id":"$id","owner":"$owner","created_at":"$createdAt","status":"ENABLED"}}"""

    init {
        test("mensagem de conta criada na fila vira conta registrada com saldo zero") {
            val accountId = UUID.randomUUID()
            val ownerId = UUID.randomUUID()

            sqsTemplate.send(TestInfra.QUEUE_NAME, accountPayload(accountId, ownerId))

            await.atMost(Duration.ofSeconds(30)) untilAsserted {
                val account = accounts.findById(accountId)
                account.shouldNotBeNull()
                account.ownerId shouldBe ownerId
                account.createdAt shouldBe Instant.ofEpochSecond(1634874339L)
                account.balance.value shouldBeEqualComparingTo BigDecimal.ZERO
            }
        }

        test("redelivery da mesma conta não duplica nem zera saldo existente") {
            val accountId = UUID.randomUUID()
            sqsTemplate.send(TestInfra.QUEUE_NAME, accountPayload(accountId))

            await.atMost(Duration.ofSeconds(30)) untilAsserted {
                accounts.findById(accountId).shouldNotBeNull()
            }

            accounts.applyCredit(accountId, BigDecimal("55.00"))
            val duplicatedBefore = meterRegistry.counter("authorizer.accounts.duplicated").count()
            sqsTemplate.send(TestInfra.QUEUE_NAME, accountPayload(accountId))

            // Espera o consumo determinístico da redelivery (métrica de duplicada)
            // e então verifica que a conta não foi sobrescrita.
            await.atMost(Duration.ofSeconds(30)) untilAsserted {
                meterRegistry.counter("authorizer.accounts.duplicated").count() shouldBe duplicatedBefore + 1
            }
            accounts.currentBalance(accountId)!! shouldBeEqualComparingTo BigDecimal("55.00")
        }

        test("mensagem malformada não bloqueia o consumo das demais") {
            val poisonId = UUID.randomUUID()
            val goodId = UUID.randomUUID()

            sqsTemplate.send(TestInfra.QUEUE_NAME, """{"account":{"id":"quebrada"}}""")
            sqsTemplate.send(TestInfra.QUEUE_NAME, accountPayload(goodId))

            await.atMost(Duration.ofSeconds(30)) untilAsserted {
                accounts.findById(goodId).shouldNotBeNull()
            }
            accounts.findById(poisonId) shouldBe null
        }
    }
}
