package br.dev.colman.authorizer.config

import org.apache.catalina.connector.Request
import org.apache.catalina.connector.Response
import org.apache.catalina.core.StandardHost
import org.apache.catalina.valves.ErrorReportValve
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TomcatConfig {

    /**
     * Última linha de defesa na renderização de erros do host: se algum erro
     * chegar à ErrorReportValve (ex.: falha no próprio dispatch de /error), a
     * resposta sai sem página HTML e sem identificação de servidor. Rejeições
     * de parse no nível do conector NÃO passam por valve alguma e respondem a
     * página mínima do Tomcat, sem versão de servidor (ver ADR-0004).
     */
    @Bean
    fun errorReportValveCustomizer(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            factory.addContextCustomizers({ context ->
                val host = context.parent as StandardHost
                // O nome registrado impede o host de adicionar a valve padrão
                // além desta (o check de startup compara nomes de classe).
                host.errorReportValveClass = QuietErrorReportValve::class.java.name
                host.addValve(QuietErrorReportValve())
            })
        }

    /**
     * ErrorReportValve que não escreve corpo algum: a resposta é só o status,
     * sem página HTML e sem versão de servidor. Daí o `report` vazio.
     */
    private class QuietErrorReportValve : ErrorReportValve() {
        override fun report(request: Request, response: Response, throwable: Throwable?) = Unit
    }
}
