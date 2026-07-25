package br.dev.colman.authorizer.adapter.inbound.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.FilterChain
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestSizeLimitFilterSpec : FunSpec({

    val teto = 1024L
    val filtro = RequestSizeLimitFilter(teto)

    /** declararTamanho=false imita corpo chunked: tamanho só se descobre lendo. */
    fun requisicao(corpo: ByteArray, declararTamanho: Boolean = true) =
        object : MockHttpServletRequest("POST", "/transactions/abc") {
            override fun getContentLengthLong(): Long = if (declararTamanho) corpo.size.toLong() else -1
        }.apply { setContent(corpo) }

    test("Content-Length acima do teto responde 413 sem chamar a cadeia") {
        val response = MockHttpServletResponse()
        var cadeiaChamada = false

        filtro.doFilter(requisicao(ByteArray(teto.toInt() + 1)), response, FilterChain { _, _ -> cadeiaChamada = true })

        response.status shouldBe HttpStatus.CONTENT_TOO_LARGE.value()
        response.contentType!! shouldContain "application/problem+json"
        response.contentAsString shouldContain "payload-too-large"
        response.contentAsString shouldContain "/transactions/abc"
        cadeiaChamada shouldBe false
    }

    test("corpo dentro do teto passa e é lido normalmente") {
        val response = MockHttpServletResponse()
        var lido = ""

        filtro.doFilter(requisicao("""{"valor":1}""".toByteArray()), response, FilterChain { req, _ ->
            lido = req.inputStream.readBytes().decodeToString()
        })

        lido shouldBe """{"valor":1}"""
        response.status shouldBe HttpStatus.OK.value()
    }

    test("corpo sem Content-Length que estoura durante a leitura vira 413") {
        // Caminho chunked: o tamanho só é descoberto lendo.
        val response = MockHttpServletResponse()

        filtro.doFilter(requisicao(ByteArray(teto.toInt() + 10), declararTamanho = false), response, FilterChain { req, _ ->
            req.inputStream.readBytes()
        })

        response.status shouldBe HttpStatus.CONTENT_TOO_LARGE.value()
        response.contentAsString shouldContain "payload-too-large"
    }

    test("corpo exatamente no teto é aceito (sem off-by-one)") {
        val response = MockHttpServletResponse()

        filtro.doFilter(requisicao(ByteArray(teto.toInt())), response, FilterChain { req, _ ->
            req.inputStream.readBytes()
        })

        response.status shouldBe HttpStatus.OK.value()
    }
})
