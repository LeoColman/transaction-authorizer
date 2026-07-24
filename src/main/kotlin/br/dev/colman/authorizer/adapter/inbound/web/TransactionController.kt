package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.adapter.inbound.web.dto.AuthorizeTransactionRequest
import br.dev.colman.authorizer.adapter.inbound.web.dto.TransactionResponse
import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionUseCase
import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
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

    @Operation(
        summary = "Autoriza uma transação de crédito ou débito",
        description = "Idempotente por transactionId: retentativas com o mesmo id recebem o resultado " +
            "da autorização original, sem alterar o saldo novamente.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Transação APROVADA (status SUCCEEDED)"),
        ApiResponse(responseCode = "422", description = "Transação RECUSADA (status FAILED, ex.: saldo insuficiente), conta desabilitada ou moeda não suportada"),
        ApiResponse(responseCode = "404", description = "Conta não encontrada"),
        ApiResponse(responseCode = "400", description = "Payload inválido"),
    )
    @PostMapping("/transactions/{transactionId}")
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

        val httpStatus = if (transaction.approved) HttpStatus.OK else HttpStatus.UNPROCESSABLE_ENTITY
        return ResponseEntity.status(httpStatus)
            .header("X-Idempotent-Replay", result.replayed.toString())
            .body(TransactionResponse.from(transaction, presentationZone))
    }
}
