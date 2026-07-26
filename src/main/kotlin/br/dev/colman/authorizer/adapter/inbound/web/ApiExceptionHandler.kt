package br.dev.colman.authorizer.adapter.inbound.web

import br.dev.colman.authorizer.domain.AccountDisabledException
import br.dev.colman.authorizer.domain.AccountNotFoundException
import br.dev.colman.authorizer.domain.IdempotencyConflictException
import br.dev.colman.authorizer.domain.UnsupportedCurrencyException
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.core.NestedRuntimeException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.TransactionSystemException
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
class ApiExceptionHandler(
    private val meterRegistry: MeterRegistry,
) : ResponseEntityExceptionHandler() {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(AccountNotFoundException::class)
    fun accountNotFound(e: AccountNotFoundException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.NOT_FOUND, "account-not-found", "Conta não encontrada", e.message, request)

    @ExceptionHandler(AccountDisabledException::class)
    fun accountDisabled(e: AccountDisabledException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "account-disabled", "Conta desabilitada", e.message, request)

    @ExceptionHandler(UnsupportedCurrencyException::class)
    fun unsupportedCurrency(e: UnsupportedCurrencyException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported-currency", "Moeda não suportada", e.message, request)

    @ExceptionHandler(IdempotencyConflictException::class)
    fun idempotencyConflict(e: IdempotencyConflictException, request: HttpServletRequest): ProblemDetail =
        problem(HttpStatus.CONFLICT, "idempotency-conflict", "Conflito de idempotência", e.message, request)

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun invalidPathVariable(e: MethodArgumentTypeMismatchException, request: HttpServletRequest): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "invalid-request",
            "Parâmetro inválido",
            "${e.name} não é um valor válido",
            request,
        )

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

    /**
     * Falta de capacidade momentânea (pool de conexões esgotado, timeout de
     * consulta) não é defeito: 503 diz ao cliente que a retentativa é válida e
     * separa saturação de bug nos alarmes de 5xx.
     *
     * As três exceções cobrem os pontos onde a saturação aparece e o dinheiro
     * ainda não se moveu: leitura fora de transação (o fast-path de replay),
     * abertura da transação (`CannotCreateTransactionException`, o caminho mais
     * comum aqui) e falha transitória dentro dela, que sofre rollback antes do
     * commit. Falha no próprio commit é `TransactionSystemException`, fora desta
     * lista de propósito: ali o resultado é incerto e sugerir retentativa seria
     * irresponsável.
     */
    @ExceptionHandler(
        CannotGetJdbcConnectionException::class,
        CannotCreateTransactionException::class,
        TransientDataAccessException::class,
    )
    fun serviceUnavailable(
        e: NestedRuntimeException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        log.warn("Capacidade esgotada ao acessar o banco recurso={}: {}", request.requestURI, e.message)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(
                problem(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "service-unavailable",
                    "Serviço temporariamente indisponível",
                    "Capacidade esgotada; tente novamente",
                ),
            )
    }

    /**
     * Falha no commit: o resultado é genuinamente incerto — a transação pode ter
     * sido efetivada no banco antes de a conexão morrer. Continua sendo 500, e
     * não 503, porque retentar às cegas moveria o dinheiro de novo.
     *
     * O que muda é a orientação. A resposta genérica dizia "tente novamente",
     * que num contrato idempotente é uma armadilha: o cliente que interpreta
     * isso como "nova tentativa, novo id" duplica a operação, já que ids
     * diferentes são intenções diferentes. Reenviar com o MESMO
     * `transactionId` é o que descobre o desfecho sem risco: se a transação
     * tinha sido efetivada, o replay devolve o resultado original.
     */
    @ExceptionHandler(TransactionSystemException::class)
    fun resultadoIncerto(e: TransactionSystemException, request: HttpServletRequest): ProblemDetail {
        log.error("Falha ao concluir a transação, resultado incerto recurso={}", request.requestURI, e)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "uncertain-result",
            "Resultado indeterminado",
            "Não foi possível confirmar o resultado. Reenvie a mesma requisição com o mesmo " +
                "transactionId para descobrir o desfecho: se ela foi efetivada, a resposta será a original.",
            request,
        )
    }

    /**
     * Espera por lock estourada (`lock_timeout`) é saturação, igual às três
     * acima, mas chega aqui sem categoria: o Postgres devolve SQLState 55P03 e
     * o tradutor do Spring não conhece essa classe, então ela viraria 500 e
     * poluiria o alarme de defeito. O cancelamento ocorre antes de qualquer
     * escrita ser confirmada, então a retentativa é segura.
     *
     * Qualquer outro SQLState não categorizado continua sendo defeito.
     */
    @ExceptionHandler(UncategorizedSQLException::class)
    fun sqlNaoCategorizado(
        e: UncategorizedSQLException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> = if (e.sqlException?.sqlState == LOCK_NOT_AVAILABLE) {
        serviceUnavailable(e, request)
    } else {
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(unexpected(e, request))
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception, request: HttpServletRequest): ProblemDetail {
        // O recurso no log é o que liga o stack trace à transação que o cliente
        // reclamou; sem ele, um 500 é um stack trace órfão.
        log.error("Erro inesperado ao processar requisição recurso={}", request.requestURI, e)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Erro interno", "Erro inesperado; tente novamente")
    }

    private fun badRequest(detail: String): ResponseEntity<Any> = ResponseEntity
        .status(HttpStatus.BAD_REQUEST)
        .body(problem(HttpStatus.BAD_REQUEST, "invalid-request", "Payload inválido", detail))

    /**
     * Toda recusa deixa rastro: contador com o motivo e uma linha de log com o
     * recurso. Sem isso, os únicos desfechos observáveis do serviço eram sucesso,
     * recusa por saldo e 5xx — quem perguntasse "o que aconteceu com a transação
     * X?" para um 404, 409 ou conta desabilitada não teria onde olhar, porque
     * esses caminhos também não gravam nada no banco.
     *
     * O motivo entra como TAG, não no nome da métrica: é o que separa recusa de
     * negócio (saldo) de conta desabilitada, que hoje compartilham o mesmo 422 e
     * seriam indistinguíveis num alarme.
     */
    private fun problem(
        status: HttpStatus,
        type: String,
        title: String,
        detail: String?,
        request: HttpServletRequest? = null,
    ): ProblemDetail {
        meterRegistry.counter("authorizer.requests.rejected", "reason", type, "status", status.value().toString())
            .increment()
        // O recurso já identifica a transação; o detalhe fica de fora do log por
        // poder conter eco do payload do cliente.
        if (request != null) log.info("Requisição recusada reason={} status={} recurso={}", type, status.value(), request.requestURI)

        return ProblemDetail.forStatus(status).apply {
            this.type = URI.create("https://transaction-authorizer/errors/$type")
            this.title = title
            this.detail = detail
        }
    }

    private companion object {
        /** `lock_not_available`: o Postgres cancelou a espera por `lock_timeout`. */
        const val LOCK_NOT_AVAILABLE = "55P03"
    }
}
