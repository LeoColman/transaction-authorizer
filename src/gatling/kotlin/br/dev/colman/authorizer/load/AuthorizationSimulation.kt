package br.dev.colman.authorizer.load

import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.constantUsersPerSec
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.listFeeder
import io.gatling.javaapi.core.CoreDsl.rampUsersPerSec
import io.gatling.javaapi.core.CoreDsl.repeat
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.CoreDsl.uniformRandomSwitch
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Teste de carga do autorizador.
 *
 * Fluxo por usuário virtual: 1 crédito de funding + 20 operações misturadas
 * (70% débito, 30% crédito) em contas sorteadas, aproximando o padrão real de
 * uso onde débitos recusados (422) são resultado legítimo.
 *
 * Knobs por variável de ambiente:
 *   BASE_URL (http://localhost:8080), SQS_ENDPOINT (http://localhost:4566),
 *   LOAD_ACCOUNTS (500), LOAD_RATE usuários/s (30), LOAD_DURATION_SECONDS (60)
 *
 * Execução: ./gradlew gatlingRun
 */
class AuthorizationSimulation : Simulation() {

    private val baseUrl = env("BASE_URL", "http://localhost:8080")
    private val sqsEndpoint = env("SQS_ENDPOINT", "http://localhost:4566")
    private val queueName = env("QUEUE_NAME", "conta-bancaria-criada")
    private val accountCount = env("LOAD_ACCOUNTS", "500").toInt()
    private val rate = env("LOAD_RATE", "30").toDouble()
    private val durationSeconds = env("LOAD_DURATION_SECONDS", "60").toLong()

    private val accountIds: List<String> = (1..accountCount).map { UUID.randomUUID().toString() }

    private fun env(key: String, fallback: String): String = System.getenv(key) ?: fallback

    /** Publica as contas na fila SQS e espera o consumidor registrá-las. */
    private fun seedAccounts() {
        SqsClient.builder()
            .endpointOverride(URI.create(sqsEndpoint))
            .region(Region.SA_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build().use { sqs ->
                val queueUrl = sqs.getQueueUrl { it.queueName(queueName) }.queueUrl()
                accountIds.chunked(10).forEachIndexed { batchIndex, chunk ->
                    val entries = chunk.mapIndexed { i, id ->
                        SendMessageBatchRequestEntry.builder()
                            .id("seed-$batchIndex-$i")
                            .messageBody(
                                """{"account":{"id":"$id","owner":"${UUID.randomUUID()}","created_at":"1634874339","status":"ENABLED"}}""",
                            )
                            .build()
                    }
                    sqs.sendMessageBatch { it.queueUrl(queueUrl).entries(entries) }
                }
            }

        awaitAccountsRegistered()
    }

    /** Considera o seed pronto quando a última conta publicada autoriza um crédito. */
    private fun awaitAccountsRegistered() {
        val http = HttpClient.newHttpClient()
        val probeAccount = accountIds.last()
        val deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis()

        while (System.currentTimeMillis() < deadline) {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/transactions/${UUID.randomUUID()}"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"accountId":"$probeAccount","type":"CREDIT","amount":{"value":0.01,"currency":"BRL"}}""",
                    ),
                )
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) return
            Thread.sleep(500)
        }
        error("Seed de contas não ficou pronto em 60s: consumidor SQS está rodando?")
    }

    private val httpProtocol = http
        .baseUrl(baseUrl)
        .contentTypeHeader("application/json")
        .acceptHeader("application/json")

    private fun newTransactionId() = exec { session -> session.set("txId", UUID.randomUUID().toString()) }

    private fun authorize(name: String, type: String, value: String, vararg expectedStatus: Int) =
        newTransactionId().exec(
            http(name)
                .post("/transactions/#{txId}")
                .body(StringBody("""{"accountId":"#{accountId}","type":"$type","amount":{"value":$value,"currency":"BRL"}}"""))
                .check(status().`in`(*expectedStatus.toTypedArray())),
        )

    private val authorizationMix = scenario("Mix de autorizações")
        .feed(listFeeder(accountIds.map { mapOf("accountId" to it) }).circular())
        .exec(authorize("credito funding", "CREDIT", "100.00", 200))
        .repeat(20).on(
            uniformRandomSwitch().on(
                // Débito pode ser aprovado (200) ou legitimamente recusado por saldo (422).
                authorize("debito 7.00", "DEBIT", "7.00", 200, 422),
                authorize("debito 5.00", "DEBIT", "5.00", 200, 422),
                authorize("credito 10.00", "CREDIT", "10.00", 200),
            ),
        )

    override fun before() {
        seedAccounts()
    }

    init {
        setUp(
            authorizationMix.injectOpen(
                rampUsersPerSec(1.0).to(rate).during(Duration.ofSeconds(15)),
                constantUsersPerSec(rate).during(Duration.ofSeconds(durationSeconds)),
            ),
        )
            .protocols(httpProtocol)
            .assertions(
                global().failedRequests().percent().lt(1.0),
                global().responseTime().percentile(99.0).lt(800),
                global().responseTime().mean().lt(200),
            )
    }
}
