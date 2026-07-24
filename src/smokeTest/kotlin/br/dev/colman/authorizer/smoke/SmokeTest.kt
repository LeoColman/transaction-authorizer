package br.dev.colman.authorizer.smoke

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import java.net.URI
import java.time.Duration
import java.util.UUID

/**
 * Testes de fumaça: validam uma instância REAL em execução (docker compose up),
 * cobrindo o caminho crítico de ponta a ponta em menos de um minuto.
 *
 * Execução: ./gradlew smokeTest (APP_URL e SQS_ENDPOINT configuráveis).
 */
class SmokeTest : FunSpec({

    val appUrl = System.getProperty("app.url", "http://localhost:8080")
    val sqsEndpoint = System.getProperty("sqs.endpoint", "http://localhost:4566")
    val queueName = System.getProperty("sqs.queue", "conta-bancaria-criada")

    val client = RestClient.builder()
        .baseUrl(appUrl)
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    fun post(transactionId: UUID, body: String): ResponseEntity<String> = client.post()
        .uri("/transactions/$transactionId")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .toEntity(String::class.java)

    fun body(accountId: UUID, type: String, value: String) =
        """{"accountId":"$accountId","type":"$type","amount":{"value":$value,"currency":"BRL"}}"""

    val accountId = UUID.randomUUID()

    test("aplicação está de pé e saudável (actuator/health UP)") {
        val health = client.get().uri("/actuator/health").retrieve().toEntity(String::class.java)
        health.statusCode shouldBe HttpStatus.OK
        health.body!! shouldContain """"status":"UP""""
    }

    test("conta publicada na fila SQS fica disponível para transacionar") {
        val sqs = SqsClient.builder()
            .endpointOverride(URI.create(sqsEndpoint))
            .region(Region.SA_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build()

        val queueUrl = sqs.getQueueUrl { it.queueName(queueName) }.queueUrl()
        val payload = """{"account":{"id":"$accountId","owner":"${UUID.randomUUID()}","created_at":"1634874339","status":"ENABLED"}}"""
        sqs.sendMessage { it.queueUrl(queueUrl).messageBody(payload) }

        await.atMost(Duration.ofSeconds(30)) untilAsserted {
            val response = post(UUID.randomUUID(), body(accountId, "CREDIT", "0.01"))
            response.statusCode shouldBe HttpStatus.OK
        }
    }

    test("crédito é aprovado com SUCCEEDED") {
        val response = post(UUID.randomUUID(), body(accountId, "CREDIT", "100.00"))
        response.statusCode shouldBe HttpStatus.OK
        response.body!! shouldContain """"status":"SUCCEEDED""""
    }

    test("débito dentro do saldo é aprovado") {
        val response = post(UUID.randomUUID(), body(accountId, "DEBIT", "50.00"))
        response.statusCode shouldBe HttpStatus.OK
        response.body!! shouldContain """"status":"SUCCEEDED""""
    }

    test("débito acima do saldo é recusado com FAILED e 422") {
        val response = post(UUID.randomUUID(), body(accountId, "DEBIT", "999999.00"))
        response.statusCode shouldBe HttpStatus.UNPROCESSABLE_CONTENT
        response.body!! shouldContain """"status":"FAILED""""
    }

    test("retentativa com o mesmo transactionId é replay idempotente") {
        val transactionId = UUID.randomUUID()
        val first = post(transactionId, body(accountId, "CREDIT", "1.00"))
        val replay = post(transactionId, body(accountId, "CREDIT", "1.00"))

        first.statusCode shouldBe HttpStatus.OK
        replay.body shouldBe first.body
        replay.headers.getFirst("X-Idempotent-Replay") shouldBe "true"
    }

    test("métricas Prometheus expostas com contadores do autorizador") {
        val metrics = client.get().uri("/actuator/prometheus").retrieve().toEntity(String::class.java)
        metrics.statusCode shouldBe HttpStatus.OK
        metrics.body!! shouldContain "authorizer_transactions"
    }
})
