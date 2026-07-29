package br.dev.colman.authorizer.adapter.inbound.sqs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient

/**
 * O indicador do consumidor só serve para alarme se alguém conseguir consultá-lo.
 * Como `show-details` é `when-authorized` e não há Spring Security no classpath,
 * nenhum chamador é autorizado e o detalhe por componente nunca aparece no
 * `/actuator/health` — quem monitora veria o agregado cair sem saber se foi banco
 * ou fila. O grupo dá um endpoint com status próprio, sem revelar detalhe algum.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SqsHealthGroupIT : FunSpec() {

    @LocalServerPort
    private var port: Int = 0

    init {
        test("o grupo sqs responde com status próprio, separado do agregado") {
            val client = RestClient.builder()
                .baseUrl("http://localhost:$port")
                .defaultStatusHandler({ true }, { _, _ -> })
                .build()

            val resposta = client.get()
                .uri("/actuator/health/sqs")
                .retrieve()
                .toEntity(String::class.java)

            resposta.statusCode shouldBe HttpStatus.OK
            resposta.body!! shouldContain """"status":"UP""""
            // Status e nada mais: o detalhe por componente continua fora do
            // alcance de quem não é autorizado, que é a decisão do ADR.
            resposta.body!! shouldNotContain "containers"
        }
    }
}
