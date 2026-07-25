package br.dev.colman.authorizer.adapter.inbound.web

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

/**
 * Defesa em profundidade contra corpos arbitrariamente grandes: o API Gateway
 * da arquitetura proposta é a primeira barreira, mas o serviço não depende dele.
 *
 * HIGHEST_PRECEDENCE: precisa rodar ANTES de qualquer filtro do framework que
 * leia o corpo (em especial o OrderedFormContentFilter do Boot, ordem -9900, que
 * drena corpo form-urlencoded de PUT/PATCH/DELETE). Content-Length acima do teto
 * responde 413 sem ler o corpo; corpo chunked é limitado enquanto é lido — se o
 * estouro escapar como IOException até este filtro (ex.: parse de form), vira
 * 413; se o converter Jackson o embrulhar (corpo JSON), o handler devolve 400.
 * O maior payload legítimo tem ~200 bytes.
 *
 * O limite depende de alguém abrir o stream, então requisições que o MVC rejeita
 * antes disso (content-type, método ou Accept incompatíveis) não passam por aqui:
 * quem contém o corpo descartado nesse caminho é `server.tomcat.max-swallow-size`,
 * alinhado a este mesmo teto.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestSizeLimitFilter(
    @Value("\${authorizer.http.max-request-bytes:16384}") private val maxBytes: Long,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.contentLengthLong > maxBytes) {
            reject(request, response)
            return
        }
        try {
            chain.doFilter(LimitedRequest(request, maxBytes), response)
        } catch (@Suppress("SwallowedException") e: RequestBodyTooLargeException) {
            if (response.isCommitted) throw e
            reject(request, response)
        }
    }

    private fun reject(request: HttpServletRequest, response: HttpServletResponse) {
        response.reset()
        response.status = HttpStatus.CONTENT_TOO_LARGE.value()
        response.contentType = "application/problem+json"
        response.characterEncoding = "UTF-8"
        // instance com o mesmo padrão dos demais Problem Details da API. A URI
        // não-decodificada nunca contém aspas/barras cruas, seguro no JSON.
        response.writer.write(
            """{"type":"https://transaction-authorizer/errors/payload-too-large",""" +
                """"title":"Corpo da requisição muito grande","status":413,""" +
                """"detail":"Corpo excede o limite de $maxBytes bytes",""" +
                """"instance":"${request.requestURI}"}""",
        )
    }

    /** Corpo excedeu o limite durante a leitura (não há Content-Length confiável). */
    private class RequestBodyTooLargeException(message: String) : IOException(message)

    private class LimitedRequest(request: HttpServletRequest, private val maxBytes: Long) :
        HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream = LimitedInputStream(super.getInputStream(), maxBytes)
    }

    private class LimitedInputStream(
        private val delegate: ServletInputStream,
        private val maxBytes: Long,
    ) : ServletInputStream() {

        private var count = 0L

        override fun read(): Int = delegate.read().also { if (it != -1) tally(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) tally(it) }

        private fun tally(bytes: Int) {
            count += bytes
            if (count > maxBytes) {
                throw RequestBodyTooLargeException("Corpo da requisição excede o limite de $maxBytes bytes")
            }
        }

        override fun isFinished(): Boolean = delegate.isFinished
        override fun isReady(): Boolean = delegate.isReady
        override fun setReadListener(listener: ReadListener?) = delegate.setReadListener(listener)
    }
}
