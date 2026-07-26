plugins {
    // Resolve e baixa o JDK do toolchain de COMPILAÇÃO quando ausente na máquina.
    // Não cobre a JVM do próprio daemon do Gradle, exigida por
    // gradle/gradle-daemon-jvm.properties: se não houver um JDK 21 instalado e
    // detectável, o daemon falha antes de compilar qualquer coisa. Daí o JDK 21
    // constar nos pré-requisitos do README, e não só o Docker.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "transaction-authorizer"
