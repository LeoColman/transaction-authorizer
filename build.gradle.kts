plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.7"
    id("io.spring.dependency-management") version "1.1.7"
    id("io.gatling.gradle") version "3.15.1.2"
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "br.dev.colman"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("io.awspring.cloud:spring-cloud-aws-dependencies:4.0.2")
    }
}

val kotestVersion = "6.2.3"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation("io.kotest:kotest-runner-junit5:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core:$kotestVersion")
    testImplementation("io.kotest:kotest-property:$kotestVersion")
    testImplementation("io.kotest:kotest-extensions-spring:$kotestVersion")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("com.ninja-squad:springmockk:5.0.1")

    // Somente no classpath da ferramenta Pitest: no classpath normal de teste a
    // extensão conflita com o discovery do JUnit Platform.
    pitest("io.kotest:kotest-extensions-pitest:$kotestVersion")
}

val integrationTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

val smokeTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

configurations["integrationTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get(), configurations.runtimeOnly.get())
configurations["smokeTestImplementation"].extendsFrom(configurations.testImplementation.get())
configurations["smokeTestRuntimeOnly"].extendsFrom(configurations.testRuntimeOnly.get(), configurations.runtimeOnly.get())

dependencies {
    "integrationTestImplementation"("org.springframework.boot:spring-boot-testcontainers")
    "integrationTestImplementation"("io.awspring.cloud:spring-cloud-aws-testcontainers")
    "integrationTestImplementation"("org.testcontainers:testcontainers-postgresql")
    "integrationTestImplementation"("org.testcontainers:testcontainers-localstack")
    "integrationTestImplementation"("org.awaitility:awaitility-kotlin")
    "smokeTestImplementation"("org.awaitility:awaitility-kotlin")
    "gatlingImplementation"("software.amazon.awssdk:sqs")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

val integrationTestTask = tasks.register<Test>("integrationTest") {
    description = "Testes de integração com Testcontainers (Postgres + LocalStack)."
    group = "verification"
    testClassesDirs = integrationTest.output.classesDirs
    classpath = integrationTest.runtimeClasspath
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("smokeTest") {
    description = "Testes de fumaça contra uma instância em execução (APP_URL)."
    group = "verification"
    testClassesDirs = smokeTest.output.classesDirs
    classpath = smokeTest.runtimeClasspath
    systemProperty("app.url", System.getProperty("app.url", System.getenv("APP_URL") ?: "http://localhost:8080"))
    systemProperty("sqs.endpoint", System.getProperty("sqs.endpoint", System.getenv("SQS_ENDPOINT") ?: "http://localhost:4566"))
    outputs.upToDateWhen { false }
}

tasks.check {
    dependsOn(integrationTestTask)
}

springBoot {
    buildInfo()
}

pitest {
    pitestVersion = "1.25.8"
    // O Boot já traz junit-platform-launcher alinhado ao Jupiter; a injeção do
    // plugin causa "unaligned versions" no discovery do JUnit Platform.
    addJUnitPlatformLauncher = false
    // Mutação onde a suíte unitária é a responsável pela correção: domínio,
    // aplicação e adaptadores com testes unitários. Adaptadores cobertos por
    // integração (SQL/controller) ficam fora: mutantes lá seriam ruído.
    targetClasses = setOf(
        "br.dev.colman.authorizer.domain.*",
        "br.dev.colman.authorizer.application.*",
        "br.dev.colman.authorizer.adapter.inbound.sqs.*",
        "br.dev.colman.authorizer.adapter.inbound.web.dto.*",
    )
    targetTests = setOf("br.dev.colman.authorizer.*")
    threads = Runtime.getRuntime().availableProcessors().coerceAtMost(8)
    outputFormats = setOf("XML", "HTML")
    timestampedReports = false
    exportLineCoverage = true
    // Null-checks sintéticos do compilador Kotlin e chamadas de log não são
    // lógica de negócio: mutá-los gera sobreviventes que nenhum teste
    // comportamental deveria matar.
    avoidCallsTo = setOf("kotlin.jvm.internal", "org.slf4j")
    // Gate: score mínimo de mutação (%). Sobreviventes conhecidos documentados no README.
    mutationThreshold = 90
}

// Modo de cobertura: -PkoverMode=unit | integration | all (padrão).
// Permite medir a contribuição de cada camada da pirâmide separadamente.
val koverMode = providers.gradleProperty("koverMode").getOrElse("all")

kover {
    currentProject {
        sources {
            // Código de teste não é código coberto: só o source set main conta.
            excludedSourceSets.addAll("integrationTest", "smokeTest", "gatling")
        }
        instrumentation {
            when (koverMode) {
                "unit" -> disabledForTestTasks.addAll("integrationTest", "smokeTest")
                "integration" -> disabledForTestTasks.addAll("test", "smokeTest")
                else -> disabledForTestTasks.add("smokeTest")
            }
        }
    }
    reports {
        filters {
            excludes {
                // Bootstrap e wiring de infraestrutura, sem lógica de negócio.
                classes("br.dev.colman.authorizer.TransactionAuthorizerApplicationKt")
                classes("br.dev.colman.authorizer.TransactionAuthorizerApplication")
                packages("br.dev.colman.authorizer.config")
            }
        }
        verify {
            rule("Cobertura mínima de linhas (unitários + integração)") {
                minBound(80)
            }
        }
    }
}
