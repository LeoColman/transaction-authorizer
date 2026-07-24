package br.dev.colman.authorizer.smoke

import com.jayway.jsonpath.JsonPath
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
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

    fun body(accountId: UUID, type: String, value: String, currency: String = "BRL") =
        """{"accountId":"$accountId","type":"$type","amount":{"value":$value,"currency":"$currency"}}"""

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

    test("resposta segue o envelope do desafio com timestamp ISO 8601 com offset") {
        val transactionId = UUID.randomUUID()
        val response = post(transactionId, body(accountId, "CREDIT", "3.33"))

        response.statusCode shouldBe HttpStatus.OK
        val json = response.body!!
        JsonPath.read<String>(json, "$.transaction.id") shouldBe transactionId.toString()
        JsonPath.read<String>(json, "$.transaction.type") shouldBe "CREDIT"
        JsonPath.read<Double>(json, "$.transaction.amount.value") shouldBe (3.33 plusOrMinus 0.001)
        JsonPath.read<String>(json, "$.transaction.amount.currency") shouldBe "BRL"
        JsonPath.read<String>(json, "$.transaction.status") shouldBe "SUCCEEDED"
        JsonPath.read<String>(json, "$.transaction.timestamp") shouldMatch
            """\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?[+-]\d{2}:\d{2}"""
        JsonPath.read<String>(json, "$.account.id") shouldBe accountId.toString()
        JsonPath.read<String>(json, "$.account.balance.currency") shouldBe "BRL"
    }

    test("saldo retornado reflete exatamente créditos e débitos aplicados") {
        val afterCredit = post(UUID.randomUUID(), body(accountId, "CREDIT", "7.00")).body!!
        val balanceAfterCredit = JsonPath.read<Double>(afterCredit, "$.account.balance.amount")

        val afterDebit = post(UUID.randomUUID(), body(accountId, "DEBIT", "2.50")).body!!
        val balanceAfterDebit = JsonPath.read<Double>(afterDebit, "$.account.balance.amount")

        balanceAfterDebit shouldBe ((balanceAfterCredit - 2.50) plusOrMinus 0.001)
    }

    test("mesmo transactionId com payload divergente responde 409 conflito de idempotência") {
        val transactionId = UUID.randomUUID()
        post(transactionId, body(accountId, "CREDIT", "2.00")).statusCode shouldBe HttpStatus.OK

        val conflict = post(transactionId, body(accountId, "DEBIT", "999.99"))

        conflict.statusCode shouldBe HttpStatus.CONFLICT
        conflict.body!! shouldContain "idempotency-conflict"
    }

    test("conta inexistente responde 404 problem detail") {
        val response = post(UUID.randomUUID(), body(UUID.randomUUID(), "CREDIT", "10.00"))
        response.statusCode shouldBe HttpStatus.NOT_FOUND
        response.body!! shouldContain "account-not-found"
    }

    test("moeda não suportada responde 422 problem detail") {
        val response = post(UUID.randomUUID(), body(accountId, "CREDIT", "1.00", currency = "USD"))
        response.statusCode shouldBe HttpStatus.UNPROCESSABLE_CONTENT
        response.body!! shouldContain "unsupported-currency"
    }

    test("payload inválido responde 400 problem detail") {
        val response = post(UUID.randomUUID(), body(accountId, "CREDIT", "0"))
        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.body!! shouldContain "invalid-request"
    }

    test("erros de protocolo preservam o status HTTP nativo (405, 404, 415)") {
        client.get().uri("/transactions/${UUID.randomUUID()}").retrieve()
            .toEntity(String::class.java).statusCode shouldBe HttpStatus.METHOD_NOT_ALLOWED

        client.get().uri("/rota-que-nao-existe").retrieve()
            .toEntity(String::class.java).statusCode shouldBe HttpStatus.NOT_FOUND

        client.post().uri("/transactions/${UUID.randomUUID()}")
            .contentType(MediaType.TEXT_PLAIN).body("nao é json").retrieve()
            .toEntity(String::class.java).statusCode shouldBe HttpStatus.UNSUPPORTED_MEDIA_TYPE
    }

    test("contrato OpenAPI publicado com o endpoint de autorização") {
        val docs = client.get().uri("/v3/api-docs").retrieve().toEntity(String::class.java)
        docs.statusCode shouldBe HttpStatus.OK
        docs.body!! shouldContain "/transactions/{transactionId}"
    }

    test("métricas Prometheus expostas com contadores do autorizador") {
        val metrics = client.get().uri("/actuator/prometheus").retrieve().toEntity(String::class.java)
        metrics.statusCode shouldBe HttpStatus.OK
        metrics.body!! shouldContain "authorizer_transactions"
    }
})
