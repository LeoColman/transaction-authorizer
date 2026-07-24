package br.dev.colman.authorizer.adapter.inbound.web.dto

import br.dev.colman.authorizer.domain.Currency
import br.dev.colman.authorizer.domain.Money
import br.dev.colman.authorizer.domain.Transaction
import br.dev.colman.authorizer.domain.TransactionStatus
import br.dev.colman.authorizer.domain.TransactionType
import br.dev.colman.authorizer.domain.UnsupportedCurrencyException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class DtoMappingSpec : FunSpec({

    val transactionId = UUID.randomUUID()
    val accountId = UUID.randomUUID()

    test("request BRL vira comando com Money em BRL") {
        val request = AuthorizeTransactionRequest(
            accountId = accountId,
            type = TransactionType.CREDIT,
            amount = AmountRequest(BigDecimal("97.07"), "BRL"),
        )

        val command = request.toCommand(transactionId)

        command.transactionId shouldBe transactionId
        command.accountId shouldBe accountId
        command.type shouldBe TransactionType.CREDIT
        command.amount shouldBe Money(BigDecimal("97.07"), Currency.BRL)
    }

    test("moeda não suportada lança UnsupportedCurrencyException (ADR-0003)") {
        val request = AuthorizeTransactionRequest(
            accountId = accountId,
            type = TransactionType.DEBIT,
            amount = AmountRequest(BigDecimal("10.00"), "USD"),
        )

        val exception = shouldThrow<UnsupportedCurrencyException> { request.toCommand(transactionId) }
        exception.currencyCode shouldBe "USD"
    }

    test("request expõe os campos recebidos (contrato de deserialização)") {
        val amount = AmountRequest(BigDecimal("97.07"), "BRL")
        val request = AuthorizeTransactionRequest(accountId, TransactionType.CREDIT, amount)

        request.accountId shouldBe accountId
        request.type shouldBe TransactionType.CREDIT
        request.amount shouldBe amount
        amount.value shouldBe BigDecimal("97.07")
        amount.currency shouldBe "BRL"
    }

    test("resposta segue o contrato do desafio, com timestamp no fuso de apresentação") {
        val transaction = Transaction(
            id = transactionId,
            accountId = accountId,
            type = TransactionType.CREDIT,
            amount = Money.brl(BigDecimal("97.07")),
            status = TransactionStatus.SUCCEEDED,
            balanceAfter = Money.brl(BigDecimal("183.12")),
            timestamp = Instant.parse("2025-07-08T18:57:55Z"),
        )

        val response = TransactionResponse.from(transaction, ZoneId.of("America/Sao_Paulo"))

        response.transaction.id shouldBe transactionId
        response.transaction.amount.value shouldBe BigDecimal("97.07")
        response.transaction.amount.currency shouldBe "BRL"
        response.transaction.status shouldBe TransactionStatus.SUCCEEDED
        response.transaction.timestamp.toString() shouldBe "2025-07-08T15:57:55-03:00"
        response.account.id shouldBe accountId
        response.account.balance.amount shouldBe BigDecimal("183.12")
        response.account.balance.currency shouldBe "BRL"
    }
})
