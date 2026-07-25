package br.dev.colman.authorizer.adapter.inbound.web

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.OutputStreamWriter
import java.io.PrintWriter

/**
 * Em HEAD o container suprime o corpo, e como nada chega a ser escrito no socket
 * ele nunca decide o enquadramento da resposta: sai sem Content-Length e sem
 * Transfer-Encoding. Um cliente HTTP/1.1 com keep-alive (healthcheck, load
 * balancer) não tem como saber que a resposta terminou e fica bloqueado até o
 * keep-alive-timeout.
 *
 * A resposta é gerada normalmente contra um buffer que só conta os bytes, e o
 * total vira o Content-Length. Assim o cabeçalho é o MESMO que o GET
 * equivalente traria, como manda a RFC 9110 §9.3.2 — anunciar zero encerraria a
 * resposta, mas mentiria sobre o tamanho do recurso para quem usa HEAD
 * justamente para descobri-lo.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class HeadResponseFramingFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!HttpMethod.HEAD.matches(request.method)) {
            chain.doFilter(request, response)
            return
        }

        val contador = ContagemDeCorpo(response)
        chain.doFilter(request, contador)

        if (response.isCommitted) return
        // Handler de recurso estático já declara o tamanho real sem escrever no
        // stream (o corpo de um HEAD não vai para o socket): sobrescrever com a
        // contagem zerada anunciaria 0 byte para um bundle de megabytes.
        val jaEnquadrada = response.getHeader(HttpHeaders.TRANSFER_ENCODING) != null ||
            response.getHeader(HttpHeaders.CONTENT_LENGTH) != null
        if (!jaEnquadrada) response.setContentLengthLong(contador.bytesEscritos())
    }

    /**
     * Descarta o corpo (que em HEAD não vai para o socket de qualquer forma) e
     * conta quantos bytes teriam sido escritos. Não delega flush: comitar aqui
     * tiraria a chance de declarar o Content-Length.
     */
    private class ContagemDeCorpo(response: HttpServletResponse) : HttpServletResponseWrapper(response) {

        private var total = 0L
        private var writer: PrintWriter? = null

        private val stream = object : ServletOutputStream() {
            override fun write(b: Int) {
                total += 1
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                total += len
            }

            override fun isReady(): Boolean = true
            override fun setWriteListener(listener: WriteListener?) = Unit
        }

        override fun getOutputStream(): ServletOutputStream = stream

        override fun getWriter(): PrintWriter = writer
            ?: PrintWriter(OutputStreamWriter(stream, characterEncoding ?: Charsets.UTF_8.name())).also { writer = it }

        override fun flushBuffer() {
            writer?.flush()
        }

        fun bytesEscritos(): Long {
            writer?.flush()
            return total
        }
    }
}
