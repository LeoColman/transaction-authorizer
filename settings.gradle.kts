plugins {
    // Resolve e baixa automaticamente o JDK do toolchain quando ausente na máquina.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "transaction-authorizer"
