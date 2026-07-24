package br.dev.colman.authorizer.application

import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionCommand
import br.dev.colman.authorizer.application.port.outbound.TransactionRepository
import br.dev.colman.authorizer.domain.DuplicateTransactionException
import br.dev.colman.authorizer.domain.IdempotencyConflictException
import br.dev.colman.authorizer.domain.Money
import br.dev.colman.authorizer.domain.Transaction
import br.dev.colman.authorizer.domain.TransactionStatus
import br.dev.colman.authorizer.domain.TransactionType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class AuthorizationServiceSpec : FunSpec({

    val transactionId = UUID.randomUUID()
    val accountId = UUID.randomUUID()

    val command = AuthorizeTransactionCommand(
        transactionId = transactionId,
        accountId = accountId,
        type = TransactionType.CREDIT,
        amount = Money.brl(BigDecimal("10.00")),
    )

    val stored = Transaction(
        id = transactionId,
        accountId = accountId,
        type = TransactionType.CREDIT,
        amount = Money.brl(BigDecimal("10.00")),
        status = TransactionStatus.SUCCEEDED,
        balanceAfter = Money.brl(BigDecimal("110.00")),
        timestamp = Instant.parse("2025-07-08T18:57:55Z"),
    )

    lateinit var executor: AuthorizationExecutor
    lateinit var transactions: TransactionRepository
    lateinit var service: AuthorizationService

    beforeTest {
        executor = mockk()
        transactions = mockk()
        service = AuthorizationService(executor, transactions)
    }

    test("primeira autorização executa e retorna replayed=false") {
        every { transactions.findById(transactionId) } returns null
        every { executor.execute(command) } returns stored

        val result = service.authorize(command)

        result.transaction shouldBe stored
        result.replayed shouldBe false
    }

    test("retentativa com o mesmo transactionId devolve o resultado original sem executar de novo (idempotência)") {
        every { transactions.findById(transactionId) } returns stored

        val result = service.authorize(command)

        result.transaction shouldBe stored
        result.replayed shouldBe true
        verify(exactly = 0) { executor.execute(any()) }
    }

    test("corrida entre requisições duplicadas: perdedora devolve o resultado gravado pela vencedora") {
        every { transactions.findById(transactionId) } returns null andThen stored
        every { executor.execute(command) } throws DuplicateTransactionException(transactionId)

        val result = service.authorize(command)

        result.transaction shouldBe stored
        result.replayed shouldBe true
    }

    test("duplicada sem registro encontrado é erro de estado (nunca deveria acontecer)") {
        every { transactions.findById(transactionId) } returns null
        every { executor.execute(command) } throws DuplicateTransactionException(transactionId)

        shouldThrow<IllegalStateException> { service.authorize(command) }
    }

    test("mesmo transactionId com valor diferente é conflito de idempotência, não replay") {
        every { transactions.findById(transactionId) } returns stored

        val divergent = command.copy(amount = Money.brl(BigDecimal("99.99")))

        shouldThrow<IdempotencyConflictException> { service.authorize(divergent) }
    }

    test("mesmo transactionId com tipo ou conta diferente é conflito de idempotência") {
        every { transactions.findById(transactionId) } returns stored

        shouldThrow<IdempotencyConflictException> {
            service.authorize(command.copy(type = TransactionType.DEBIT))
        }
        shouldThrow<IdempotencyConflictException> {
            service.authorize(command.copy(accountId = UUID.randomUUID()))
        }
    }

    test("corrida com payload divergente também é conflito, não replay silencioso") {
        val divergent = command.copy(amount = Money.brl(BigDecimal("99.99")))
        every { transactions.findById(transactionId) } returns null andThen stored
        every { executor.execute(divergent) } throws DuplicateTransactionException(transactionId)

        shouldThrow<IdempotencyConflictException> { service.authorize(divergent) }
    }

    test("comando rejeita valor zero ou negativo (invariante do caso de uso)") {
        shouldThrow<IllegalArgumentException> {
            command.copy(amount = Money.brl(BigDecimal.ZERO))
        }
        shouldThrow<IllegalArgumentException> {
            command.copy(amount = Money.brl(BigDecimal("-1.00")))
        }
    }

    test("mesmo valor com escala diferente ainda é replay legítimo (10.0 == 10.00)") {
        every { transactions.findById(transactionId) } returns stored

        val result = service.authorize(command.copy(amount = Money.brl(BigDecimal("10.0"))))

        result.replayed shouldBe true
    }
})
