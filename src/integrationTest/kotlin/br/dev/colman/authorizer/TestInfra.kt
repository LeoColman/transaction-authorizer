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

        localstack.execInContainer("awslocal", "sqs", "create-queue", "--queue-name", QUEUE_NAME, "--region", REGION)

        System.setProperty("spring.datasource.url", postgres.jdbcUrl)
        System.setProperty("spring.datasource.username", postgres.username)
        System.setProperty("spring.datasource.password", postgres.password)
        System.setProperty("spring.cloud.aws.endpoint", localstack.getEndpointOverride(LocalStackContainer.Service.SQS).toString())
        System.setProperty("spring.cloud.aws.region.static", REGION)
        System.setProperty("spring.cloud.aws.credentials.access-key", localstack.accessKey)
        System.setProperty("spring.cloud.aws.credentials.secret-key", localstack.secretKey)
    }
}
