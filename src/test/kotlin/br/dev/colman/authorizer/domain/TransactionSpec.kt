package br.dev.colman.authorizer.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class TransactionSpec : FunSpec({

    fun transaction(
        amount: BigDecimal = BigDecimal("10.00"),
        balanceAfter: BigDecimal = BigDecimal("100.00"),
        status: TransactionStatus = TransactionStatus.SUCCEEDED,
    ) = Transaction(
        id = UUID.randomUUID(),
        accountId = UUID.randomUUID(),
        type = TransactionType.CREDIT,
        amount = Money.brl(amount),
        status = status,
        balanceAfter = Money.brl(balanceAfter),
        timestamp = Instant.parse("2025-07-08T18:57:55Z"),
    )

    test("transação aprovada tem status SUCCEEDED") {
        transaction(status = TransactionStatus.SUCCEEDED).approved shouldBe true
        transaction(status = TransactionStatus.FAILED).approved shouldBe false
    }

    test("rejeita valor de transação zero ou negativo") {
        shouldThrow<IllegalArgumentException> { transaction(amount = BigDecimal.ZERO) }
        shouldThrow<IllegalArgumentException> { transaction(amount = BigDecimal("-1.00")) }
    }

    test("rejeita saldo resultante negativo (invariante do domínio)") {
        shouldThrow<IllegalArgumentException> { transaction(balanceAfter = BigDecimal("-0.01")) }
    }

    test("saldo resultante zero é válido (débito que zera a conta)") {
        transaction(balanceAfter = BigDecimal("0.00")).balanceAfter shouldBe Money.zero()
    }
})
