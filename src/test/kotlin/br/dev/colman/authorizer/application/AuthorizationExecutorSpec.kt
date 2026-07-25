package br.dev.colman.authorizer.application

import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionCommand
import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.application.port.outbound.TransactionRepository
import br.dev.colman.authorizer.domain.Account
import br.dev.colman.authorizer.domain.AccountDisabledException
import br.dev.colman.authorizer.domain.AccountNotFoundException
import br.dev.colman.authorizer.domain.AccountStatus
import br.dev.colman.authorizer.domain.Money
import br.dev.colman.authorizer.domain.Transaction
import br.dev.colman.authorizer.domain.TransactionStatus
import br.dev.colman.authorizer.domain.TransactionType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class AuthorizationExecutorSpec : FunSpec({

    val now = Instant.parse("2025-07-08T18:57:55Z")
    val accountId = UUID.fromString("5b19c8b6-0cc4-4c72-a989-0c2ee15fa975")
    val transactionId = UUID.fromString("8e8ae808-b154-48b5-9f3e-553935cc4543")

    fun account(status: AccountStatus = AccountStatus.ENABLED, balance: String = "100.00") = Account(
        id = accountId,
        ownerId = UUID.randomUUID(),
        status = status,
        createdAt = now,
        balance = Money.brl(BigDecimal(balance)),
    )

    fun command(type: TransactionType, value: String = "10.00") = AuthorizeTransactionCommand(
        transactionId = transactionId,
        accountId = accountId,
        type = type,
        amount = Money.brl(BigDecimal(value)),
    )

    lateinit var accounts: AccountRepository
    lateinit var transactions: TransactionRepository
    lateinit var executor: AuthorizationExecutor

    beforeTest {
        accounts = mockk()
        transactions = mockk(relaxUnitFun = true)
        executor = AuthorizationExecutor(accounts, transactions, Clock.fixed(now, ZoneOffset.UTC))
    }

    test("crédito aprovado soma o valor e grava transação SUCCEEDED") {
        every { accounts.findById(accountId) } returns account(balance = "100.00")
        every { accounts.applyCredit(accountId, BigDecimal("10.00")) } returns BigDecimal("110.00")

        val result = executor.execute(command(TransactionType.CREDIT))

        result.status shouldBe TransactionStatus.SUCCEEDED
        result.balanceAfter shouldBe Money.brl(BigDecimal("110.00"))
        result.timestamp shouldBe now

        val saved = slot<Transaction>()
        verify(exactly = 1) { transactions.insert(capture(saved)) }
        saved.captured.id shouldBe transactionId
        saved.captured.status shouldBe TransactionStatus.SUCCEEDED
    }

    test("débito com saldo suficiente subtrai o valor e grava SUCCEEDED") {
        every { accounts.findById(accountId) } returns account(balance = "100.00")
        every { accounts.applyDebit(accountId, BigDecimal("40.00")) } returns BigDecimal("60.00")

        val result = executor.execute(command(TransactionType.DEBIT, "40.00"))

        result.status shouldBe TransactionStatus.SUCCEEDED
        result.balanceAfter shouldBe Money.brl(BigDecimal("60.00"))
    }

    test("débito recusado por saldo insuficiente grava FAILED sem alterar saldo e relê o saldo atual") {
        every { accounts.findById(accountId) } returns account(balance = "30.00")
        every { accounts.applyDebit(accountId, BigDecimal("40.00")) } returns null
        every { accounts.currentBalance(accountId) } returns BigDecimal("30.00")

        val result = executor.execute(command(TransactionType.DEBIT, "40.00"))

        result.status shouldBe TransactionStatus.FAILED
        result.balanceAfter shouldBe Money.brl(BigDecimal("30.00"))

        val saved = slot<Transaction>()
        verify(exactly = 1) { transactions.insert(capture(saved)) }
        saved.captured.status shouldBe TransactionStatus.FAILED
    }

    test("crédito recusado por estourar o teto de saldo grava FAILED sem alterar saldo") {
        every { accounts.findById(accountId) } returns account(balance = "99999999999999995.00")
        every { accounts.applyCredit(accountId, BigDecimal("10.00")) } returns null
        every { accounts.currentBalance(accountId) } returns BigDecimal("99999999999999995.00")

        val result = executor.execute(command(TransactionType.CREDIT))

        result.status shouldBe TransactionStatus.FAILED
        result.balanceAfter shouldBe Money.brl(BigDecimal("99999999999999995.00"))

        val saved = slot<Transaction>()
        verify(exactly = 1) { transactions.insert(capture(saved)) }
        saved.captured.status shouldBe TransactionStatus.FAILED
    }

    test("débito do valor exato do saldo é aprovado e zera a conta (corner case)") {
        every { accounts.findById(accountId) } returns account(balance = "40.00")
        every { accounts.applyDebit(accountId, BigDecimal("40.00")) } returns BigDecimal("0.00")

        val result = executor.execute(command(TransactionType.DEBIT, "40.00"))

        result.status shouldBe TransactionStatus.SUCCEEDED
        result.balanceAfter shouldBe Money.brl(BigDecimal("0.00"))
    }

    test("débito recusado com conta ausente na releitura de saldo lança AccountNotFoundException") {
        every { accounts.findById(accountId) } returns account(balance = "30.00")
        every { accounts.applyDebit(accountId, BigDecimal("40.00")) } returns null
        every { accounts.currentBalance(accountId) } returns null

        shouldThrow<AccountNotFoundException> { executor.execute(command(TransactionType.DEBIT, "40.00")) }

        verify(exactly = 0) { transactions.insert(any()) }
    }

    test("conta inexistente lança AccountNotFoundException e nada é gravado") {
        every { accounts.findById(accountId) } returns null

        shouldThrow<AccountNotFoundException> { executor.execute(command(TransactionType.CREDIT)) }

        verify(exactly = 0) { transactions.insert(any()) }
    }

    test("conta desabilitada lança AccountDisabledException e nada é gravado") {
        every { accounts.findById(accountId) } returns account(status = AccountStatus.DISABLED)

        shouldThrow<AccountDisabledException> { executor.execute(command(TransactionType.CREDIT)) }

        verify(exactly = 0) { transactions.insert(any()) }
    }
})
