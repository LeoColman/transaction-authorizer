package br.dev.colman.authorizer.adapter.inbound.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Em HEAD o container suprime o corpo, e como nada é escrito ele nunca decide o
 * enquadramento da resposta: sai sem Content-Length e sem Transfer-Encoding. Um
 * cliente HTTP/1.1 com keep-alive (healthcheck, load balancer) não tem como saber
 * que a resposta terminou e fica bloqueado até o keep-alive-timeout.
 *
 * Declarar Content-Length: 0 quando nenhum enquadramento foi definido encerra a
 * resposta explicitamente, como manda a RFC 9110 §9.3.2.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class HeadResponseFramingFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (!HttpMethod.HEAD.matches(request.method)) {
            chain.doFilter(request, response)
            return
        }

        // Declarado ANTES da cadeia: o corpo de um HEAD é descartado pelo
        // container e a resposta pode ser comitada dentro do handler, tarde
        // demais para acrescentar cabeçalho. Um handler que defina o próprio
        // Content-Length sobrescreve este valor.
        response.setContentLength(0)
        chain.doFilter(request, response)

        if (!response.isCommitted &&
            response.getHeader(HttpHeaders.CONTENT_LENGTH) == null &&
            response.getHeader(HttpHeaders.TRANSFER_ENCODING) == null
        ) {
            response.setContentLength(0)
        }
    }
}
