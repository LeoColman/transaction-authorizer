package br.dev.colman.authorizer

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.extensions.spring.SpringExtension

class ProjectConfig : AbstractProjectConfig() {

    override val extensions: List<Extension> = listOf(SpringExtension())

    override suspend fun beforeProject() {
        TestInfra.start()
    }
}
