package br.dev.colman.authorizer.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.yaml.snakeyaml.Yaml

/**
 * O fail-fast de configuração é a única proteção contra o pior modo de falha do
 * serviço: subir saudável apontando para o lugar errado — banco que não é o de
 * produção, ou fila criada em branco por causa de um nome digitado errado, com
 * health check verde e ninguém percebendo (ADR-0005).
 *
 * Nenhum outro teste cobre isso, e por construção: toda a suíte de integração
 * injeta as conexões dos Testcontainers antes do contexto subir, e o compose usa
 * o perfil `local`. O caminho de produção — sem env var nenhuma — nunca é
 * exercitado. Estas asserções são sobre o arquivo de configuração justamente por
 * isso: elas pegam a regressão real, que é alguém acrescentar um default
 * "inofensivo" ao documento base.
 */
class FailFastConfigSpec : FunSpec({

    val documentos: List<Map<String, Any>> = FailFastConfigSpec::class.java
        .getResourceAsStream("/application.yml")!!
        .use { Yaml().loadAll(it).toList() }
        .filterIsInstance<Map<String, Any>>()

    val base = documentos.first()
    val local = documentos.single { doc ->
        @Suppress("UNCHECKED_CAST")
        val config = (doc["spring"] as? Map<String, Any>)?.get("config") as? Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        ((config?.get("activate") as? Map<String, Any>)?.get("on-profile")) == "local"
    }

    @Suppress("UNCHECKED_CAST")
    fun Map<String, Any>.caminho(vararg chaves: String): Any? =
        chaves.fold(this as Any?) { atual, chave -> (atual as? Map<String, Any>)?.get(chave) }

    test("credenciais de banco no documento base são placeholders sem default") {
        listOf("url", "username", "password").forEach { propriedade ->
            val valor = base.caminho("spring", "datasource", propriedade) as String
            // "${DB_URL}" falha; "${DB_URL:jdbc:...}" subiria com um default silencioso.
            valor.contains(':') shouldBe false
        }
    }

    test("perfil local tem os defaults, para o desenvolvimento não depender de env var") {
        val url = local.caminho("spring", "datasource", "url") as String
        url.contains(":jdbc:postgresql://") shouldBe true
    }

    test("estratégia de fila inexistente só é CREATE dentro do perfil local") {
        local.caminho("authorizer", "sqs", "queue-not-found-strategy") shouldBe "CREATE"
        // Fora do perfil local vale o padrão FAIL codificado em SqsConfig: um
        // valor aqui no documento base valeria para produção também.
        base.caminho("authorizer", "sqs", "queue-not-found-strategy") shouldBe null
    }

    test("a fila consumida vem de env var, sem nome fixo em código") {
        val fila = base.caminho("authorizer", "sqs", "account-created-queue") as String
        fila.startsWith("\${ACCOUNT_CREATED_QUEUE:") shouldBe true
        fila shouldNotBe "conta-bancaria-criada"
    }
})
