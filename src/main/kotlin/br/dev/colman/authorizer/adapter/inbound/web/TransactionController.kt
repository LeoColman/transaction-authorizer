package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.adapter.inbound.web.dto.AuthorizeTransactionRequest
import br.dev.colman.authorizer.adapter.inbound.web.dto.TransactionResponse
import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionUseCase
import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId
import java.util.UUID

@RestController
@Tag(name = "Transações", description = "Autorização de transações financeiras")
class TransactionController(
    private val authorizeTransaction: AuthorizeTransactionUseCase,
    private val presentationZone: ZoneId,
    private val meterRegistry: MeterRegistry,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        const val PROBLEM_JSON = "application/problem+json"
    }

    @Operation(
        summary = "Autoriza uma transação de crédito ou débito",
        description = "Idempotente por transactionId: retentativas com o mesmo id recebem o resultado " +
            "da autorização original, sem alterar o saldo novamente.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Transação APROVADA (status SUCCEEDED)"),
        ApiResponse(
            responseCode = "422",
            description = "Transação RECUSADA por saldo insuficiente (envelope completo, status FAILED); " +
                "conta desabilitada ou moeda não suportada respondem Problem Details (RFC 9457)",
            content = [
                Content(mediaType = "application/json", schema = Schema(implementation = TransactionResponse::class)),
                Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class)),
            ],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Conta não encontrada",
            content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "409",
            description = "Mesmo transactionId com payload divergente do original",
            content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "400",
            description = "Payload inválido",
            content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
        ),
        ApiResponse(
            responseCode = "406",
            description = "Accept não comporta application/json; rejeitado antes de executar a autorização",
            content = [Content(mediaType = PROBLEM_JSON, schema = Schema(implementation = ProblemDetail::class))],
        ),
    )
    // produces explícito: Accept que não comporta application/json é rejeitado
    // (406) na fase de handler mapping, ANTES de executar a autorização. Sem
    // isso, a negociação só falharia na escrita da resposta, depois do commit:
    // o dinheiro se moveria e o chamador receberia erro. Qvalues (ex.: q=0)
    // não participam da decisão do Spring MVC; servir a única representação
    // nesses casos é permitido pela RFC 9110 §12.5.1.
    @PostMapping("/transactions/{transactionId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun authorize(
        @PathVariable transactionId: UUID,
        @Valid @RequestBody request: AuthorizeTransactionRequest,
    ): ResponseEntity<TransactionResponse> {
        val result = authorizeTransaction.authorize(request.toCommand(transactionId))
        val transaction = result.transaction

        meterRegistry.counter(
            "authorizer.transactions",
            "type", transaction.type.name,
            "status", transaction.status.name,
            "replayed", result.replayed.toString(),
        ).increment()

        log.info(
            "Autorização concluída transactionId={} accountId={} type={} status={} replayed={}",
            transaction.id, transaction.accountId, transaction.type, transaction.status, result.replayed,
        )

        val httpStatus = if (transaction.approved) HttpStatus.OK else HttpStatus.UNPROCESSABLE_CONTENT
        return ResponseEntity.status(httpStatus)
            .header("X-Idempotent-Replay", result.replayed.toString())
            .body(TransactionResponse.from(transaction, presentationZone))
    }
}
