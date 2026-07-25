package br.dev.colman.authorizer.adapter.inbound.web

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.FilterChain
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.IOException

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

    test("estouro depois da resposta comitada propaga o erro em vez de reescrever") {
        // Com bytes já no socket, response.reset() lançaria IllegalStateException
        // e trocaria o erro real por um erro de framework. O filtro devolve a
        // falha original e deixa o container encerrar a conexão.
        val response = MockHttpServletResponse()

        val erro = shouldThrow<IOException> {
            filtro.doFilter(
                requisicao(ByteArray(teto.toInt() + 10), declararTamanho = false),
                response,
                FilterChain { req, resp ->
                    resp.writer.write("resposta parcial")
                    resp.flushBuffer()
                    req.inputStream.readBytes()
                },
            )
        }

        erro.message!! shouldContain "excede o limite"
        response.status shouldBe HttpStatus.OK.value()
        response.contentAsString shouldBe "resposta parcial"
    }

    test("rejeição descarta o que o handler já tinha escrito no buffer") {
        // Sem reset(), o 413 sairia grudado no corpo parcial da resposta
        // anterior, gerando JSON inválido para o cliente.
        val response = MockHttpServletResponse()

        filtro.doFilter(requisicao(ByteArray(teto.toInt() + 10), declararTamanho = false), response, FilterChain { req, resp ->
            resp.writer.write("""{"lixo":"de um handler que comecou a responder"}""")
            req.inputStream.readBytes()
        })

        response.status shouldBe HttpStatus.CONTENT_TOO_LARGE.value()
        response.contentAsString shouldNotContain "lixo"
        response.characterEncoding.uppercase() shouldBe "UTF-8"
    }

    test("limite vale também para leitura byte a byte") {
        // InputStream.read() sem buffer passa pelo outro overload do contador.
        val response = MockHttpServletResponse()

        filtro.doFilter(requisicao(ByteArray(teto.toInt() + 1), declararTamanho = false), response, FilterChain { req, _ ->
            @Suppress("EmptyWhileBlock")
            while (req.inputStream.read() != -1) { }
        })

        response.status shouldBe HttpStatus.CONTENT_TOO_LARGE.value()
    }

    test("corpo exatamente no teto é aceito (sem off-by-one)") {
        val response = MockHttpServletResponse()

        filtro.doFilter(requisicao(ByteArray(teto.toInt())), response, FilterChain { req, _ ->
            req.inputStream.readBytes()
        })

        response.status shouldBe HttpStatus.OK.value()
    }
})
