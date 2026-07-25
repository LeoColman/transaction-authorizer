package br.dev.colman.authorizer.adapter.inbound.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class HeadResponseFramingFilterSpec : FunSpec({

    val filtro = HeadResponseFramingFilter()

    fun requisicao(metodo: String) = MockHttpServletRequest(metodo, "/qualquer")

    test("HEAD sem enquadramento anuncia o tamanho que o corpo teria") {
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, resp -> resp.writer.write("corpo de resposta") }

        filtro.doFilter(requisicao("HEAD"), response, chain)

        response.getHeader(HttpHeaders.CONTENT_LENGTH) shouldBe "17"
    }

    test("HEAD sem corpo algum anuncia zero, encerrando a resposta") {
        val response = MockHttpServletResponse()

        filtro.doFilter(requisicao("HEAD"), response, FilterChain { _, _ -> })

        response.getHeader(HttpHeaders.CONTENT_LENGTH) shouldBe "0"
    }

    test("HEAD não sobrescreve Content-Length que o handler já declarou") {
        // Handler de recurso estático declara o tamanho real sem escrever no
        // stream: contar zero bytes e sobrescrever anunciaria recurso vazio.
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, resp -> (resp as HttpServletResponse).setContentLength(4242) }

        filtro.doFilter(requisicao("HEAD"), response, chain)

        response.getHeader(HttpHeaders.CONTENT_LENGTH) shouldBe "4242"
    }

    test("HEAD não interfere quando a resposta já usa Transfer-Encoding") {
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, resp ->
            (resp as HttpServletResponse).setHeader(HttpHeaders.TRANSFER_ENCODING, "chunked")
        }

        filtro.doFilter(requisicao("HEAD"), response, chain)

        response.getHeader(HttpHeaders.CONTENT_LENGTH) shouldBe null
    }

    test("conta bytes escritos direto no stream, inclusive um a um") {
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, resp ->
            resp.outputStream.write("abc".toByteArray())
            resp.outputStream.write('!'.code)
        }

        filtro.doFilter(requisicao("HEAD"), response, chain)

        response.getHeader(HttpHeaders.CONTENT_LENGTH) shouldBe "4"
    }

    test("writer é reaproveitado entre chamadas, sem perder o que já foi escrito") {
        // Criar um PrintWriter novo a cada getWriter() descartaria o buffer do
        // anterior: o tamanho anunciado sairia menor que o corpo real.
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, resp ->
            resp.writer.write("12345")
            resp.writer.write("678")
        }

        filtro.doFilter(requisicao("HEAD"), response, chain)

        response.getHeader(HttpHeaders.CONTENT_LENGTH) shouldBe "8"
    }

    test("flushBuffer do handler não impede o enquadramento") {
        // O wrapper segura o flush de propósito: comitar durante a cadeia
        // tiraria a chance de declarar Content-Length depois dela.
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, resp ->
            resp.writer.write("corpo")
            resp.flushBuffer()
        }

        filtro.doFilter(requisicao("HEAD"), response, chain)

        response.getHeader(HttpHeaders.CONTENT_LENGTH) shouldBe "5"
    }

    test("outros métodos passam intactos, sem o wrapper contador") {
        val response = MockHttpServletResponse()
        val chain = FilterChain { _, resp -> resp.writer.write("""{"ok":true}""") }

        filtro.doFilter(requisicao("POST"), response, chain)

        response.contentAsString shouldBe """{"ok":true}"""
    }
})
