package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.domain.AccountDisabledException
import br.dev.colman.authorizer.domain.AccountNotFoundException
import br.dev.colman.authorizer.domain.IdempotencyConflictException
import br.dev.colman.authorizer.domain.UnsupportedCurrencyException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingPathVariableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import java.net.URI

/**
 * Erros no formato RFC 9457 (Problem Details). Recusa por saldo insuficiente
 * NÃO passa por aqui: é resultado de negócio, respondido com o envelope
 * completo da transação e HTTP 422 (ADR-0004).
 *
 * Estende ResponseEntityExceptionHandler para que exceções do próprio framework
 * (método não suportado, rota inexistente, content-type inválido, ...) preservem
 * o status HTTP nativo (405/404/415) em vez de caírem no tratador de 500.
 */
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AccountNotFoundException::class)
    fun accountNotFound(e: AccountNotFoundException): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "account-not-found", "Conta não encontrada", e.message)

    @ExceptionHandler(AccountDisabledException::class)
    fun accountDisabled(e: AccountDisabledException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "account-disabled", "Conta desabilitada", e.message)

    @ExceptionHandler(UnsupportedCurrencyException::class)
    fun unsupportedCurrency(e: UnsupportedCurrencyException): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported-currency", "Moeda não suportada", e.message)

    @ExceptionHandler(IdempotencyConflictException::class)
    fun idempotencyConflict(e: IdempotencyConflictException): ProblemDetail =
        problem(HttpStatus.CONFLICT, "idempotency-conflict", "Conflito de idempotência", e.message)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidPathVariable(e: MethodArgumentTypeMismatchException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST, "invalid-request", "Parâmetro inválido", "${e.name} não é um valor válido")

    override fun handleMethodArgumentNotValid(
        e: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val detail = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return badRequest(detail)
    }

    override fun handleHttpMessageNotReadable(
        e: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = badRequest("Corpo da requisição malformado ou com valores inválidos")

    override fun handleMissingPathVariable(
        e: MissingPathVariableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? = if (e.isMissingAfterConversion) {
        // Path variable presente mas convertida para null (ex.: segmento em
        // branco): mesmo formato de erro dos demais parâmetros inválidos.
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(problem(HttpStatus.BAD_REQUEST, "invalid-request", "Parâmetro inválido", "${e.variableName} não é um valor válido"))
    } else {
        super.handleMissingPathVariable(e, headers, status, request)
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ProblemDetail {
        log.error("Erro inesperado ao processar requisição", e)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Erro interno", "Erro inesperado; tente novamente")
    }

    private fun badRequest(detail: String): ResponseEntity<Any> = ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(problem(HttpStatus.BAD_REQUEST, "invalid-request", "Payload inválido", detail))

    private fun problem(status: HttpStatus, type: String, title: String, detail: String?): ProblemDetail =
        ProblemDetail.forStatus(status).apply {
            this.type = URI.create("https://transaction-authorizer/errors/$type")
            this.title = title
            this.detail = detail
        }
}
