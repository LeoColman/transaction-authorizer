package br.dev.colman.authorizer

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName

/**
 * Containers compartilhados por todos os testes de integração (singleton):
 * iniciar Postgres e LocalStack uma única vez mantém a suíte rápida.
 * As propriedades do Spring são definidas como system properties antes de
 * qualquer contexto subir (ver ProjectConfig).
 */
object TestInfra {

    const val QUEUE_NAME = "conta-bancaria-criada"
    const val DLQ_NAME = "conta-bancaria-criada-dlq"

    /** Fila só do teste de redrive: visibility curto para não esperar 30s pela reentrega. */
    const val FALHA_QUEUE_NAME = "fila-que-sempre-falha"
    const val FALHA_DLQ_NAME = "fila-que-sempre-falha-dlq"
    const val MAX_RECEIVE_COUNT = 2

    private const val REGION = "sa-east-1"

    val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
        .withDatabaseName("authorizer")
        .withUsername("authorizer")
        .withPassword("authorizer")

    val localstack: LocalStackContainer = LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7.2"))
        .withServices(LocalStackContainer.Service.SQS)

    fun start() {
        postgres.start()
        localstack.start()

        criarFila(QUEUE_NAME)
        criarFila(DLQ_NAME)
        criarFila(FALHA_DLQ_NAME)
        criarFilaComRedrive(FALHA_QUEUE_NAME, FALHA_DLQ_NAME)

        System.setProperty("spring.datasource.url", postgres.jdbcUrl)
        System.setProperty("spring.datasource.username", postgres.username)
        System.setProperty("spring.datasource.password", postgres.password)
        System.setProperty("spring.cloud.aws.endpoint", localstack.getEndpointOverride(LocalStackContainer.Service.SQS).toString())
        System.setProperty("spring.cloud.aws.region.static", REGION)
        System.setProperty("spring.cloud.aws.credentials.access-key", localstack.accessKey)
        System.setProperty("spring.cloud.aws.credentials.secret-key", localstack.secretKey)
    }

    private fun criarFila(nome: String, vararg atributos: String) {
        localstack.execInContainer(
            "awslocal", "sqs", "create-queue", "--queue-name", nome, "--region", REGION, *atributos,
        )
    }

    /**
     * Reproduz o que o hook do LocalStack faz no compose (e o IaC faria em
     * produção): a fila nasce apontando para a DLQ. maxReceiveCount menor e
     * visibility de 1s só para o teste não levar 30s por tentativa.
     */
    private fun criarFilaComRedrive(nome: String, dlq: String) {
        val arn = localstack.execInContainer(
            "awslocal", "sqs", "get-queue-attributes",
            "--queue-url", urlDaFila(dlq),
            "--attribute-names", "QueueArn", "--region", REGION,
            "--query", "Attributes.QueueArn", "--output", "text",
        ).stdout.trim()

        criarFila(
            nome,
            "--attributes",
            """{"VisibilityTimeout":"1","RedrivePolicy":"{\"deadLetterTargetArn\":\"$arn\",""" +
                """\"maxReceiveCount\":\"$MAX_RECEIVE_COUNT\"}"}""",
        )
    }

    private fun urlDaFila(nome: String): String = localstack.execInContainer(
        "awslocal", "sqs", "get-queue-url", "--queue-name", nome, "--region", REGION, "--output", "text",
    ).stdout.trim()
}
