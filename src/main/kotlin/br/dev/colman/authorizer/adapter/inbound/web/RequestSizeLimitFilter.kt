package br.dev.colman.authorizer.adapter.inbound.web

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

/**
 * Defesa em profundidade contra corpos arbitrariamente grandes: o API Gateway
 * da arquitetura proposta é a primeira barreira, mas o serviço não depende
 * dele. Content-Length acima do teto responde 413 sem ler o corpo; corpo
 * chunked (sem Content-Length) é limitado durante a leitura e o estouro vira
 * 400 de corpo malformado. O maior payload legítimo da API tem ~200 bytes.
 */
@Component
class RequestSizeLimitFilter(
    @Value("\${authorizer.http.max-request-bytes:16384}") private val maxBytes: Long,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.contentLengthLong > maxBytes) {
            reject(response)
            return
        }
        chain.doFilter(LimitedRequest(request, maxBytes), response)
    }

    private fun reject(response: HttpServletResponse) {
        response.status = HttpStatus.CONTENT_TOO_LARGE.value()
        response.contentType = "application/problem+json"
        response.characterEncoding = "UTF-8"
        response.writer.write(
            """{"type":"https://transaction-authorizer/errors/payload-too-large",""" +
                """"title":"Corpo da requisição muito grande","status":413,""" +
                """"detail":"Corpo excede o limite de $maxBytes bytes"}""",
        )
    }

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
                throw IOException("Corpo da requisição excede o limite de $maxBytes bytes")
            }
        }

        override fun isFinished(): Boolean = delegate.isFinished
        override fun isReady(): Boolean = delegate.isReady
        override fun setReadListener(listener: ReadListener?) = delegate.setReadListener(listener)
    }
}
