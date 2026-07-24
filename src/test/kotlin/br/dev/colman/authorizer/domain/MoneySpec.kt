package br.dev.colman.authorizer.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import java.math.BigDecimal

class MoneySpec : FunSpec({

    test("aceita valores com até 2 casas decimais") {
        Money.brl(BigDecimal("10.05")).value shouldBe BigDecimal("10.05")
        Money.brl(BigDecimal("10.5")).normalized shouldBe BigDecimal("10.50")
        Money.brl(BigDecimal("10")).normalized shouldBe BigDecimal("10.00")
    }

    test("rejeita valores com mais de 2 casas decimais (sem arredondamento silencioso)") {
        shouldThrow<IllegalArgumentException> { Money.brl(BigDecimal("10.005")) }
        shouldThrow<IllegalArgumentException> { Money.brl(BigDecimal("0.001")) }
    }

    test("soma e subtração preservam a moeda e a escala") {
        val a = Money.brl(BigDecimal("10.10"))
        val b = Money.brl(BigDecimal("0.90"))

        (a + b) shouldBe Money.brl(BigDecimal("11.00"))
        (a - b) shouldBe Money.brl(BigDecimal("9.20"))
    }

    test("zero tem escala 2 e não é positivo nem negativo") {
        val zero = Money.zero()
        zero.value shouldBe BigDecimal("0.00")
        zero.isPositive() shouldBe false
        zero.isNegative() shouldBe false
    }

    test("subtração pode resultar em valor negativo (quem impede é a autorização, não o tipo)") {
        val result = Money.brl(BigDecimal("5.00")) - Money.brl(BigDecimal("7.50"))
        result.isNegative() shouldBe true
        result.value shouldBe BigDecimal("-2.50")
    }

    context("propriedades") {
        val centavos = Arb.long(-1_000_000_000L, 1_000_000_000L)
        val dinheiro = centavos.map { Money.brl(BigDecimal.valueOf(it).movePointLeft(2)) }

        test("soma é comutativa") {
            checkAll(dinheiro, dinheiro) { a, b ->
                (a + b) shouldBe (b + a)
            }
        }

        test("subtrair o próprio valor zera") {
            checkAll(dinheiro) { a ->
                (a - a).value.signum() shouldBe 0
            }
        }

        test("normalized sempre tem escala 2") {
            checkAll(dinheiro) { a ->
                a.normalized.scale() shouldBe 2
            }
        }
    }
})
