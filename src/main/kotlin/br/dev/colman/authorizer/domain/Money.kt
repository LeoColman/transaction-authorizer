package br.dev.colman.authorizer.domain

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Moedas suportadas pelo autorizador (ISO 4217).
 * Contas nascem em BRL; transações em outras moedas são rejeitadas (ADR-0003).
 */
enum class Currency {
    BRL,
}

/**
 * Valor monetário com escala fixa de 2 casas decimais.
 * Valores com mais de 2 casas decimais são rejeitados: arredondamento
 * silencioso é inaceitável em movimentação financeira.
 */
data class Money(val value: BigDecimal, val currency: Currency) {

    init {
        require(value.scale() <= SCALE) {
            "Valor monetário não pode ter mais de $SCALE casas decimais: $value"
        }
    }

    val normalized: BigDecimal
        get() = value.setScale(SCALE, RoundingMode.UNNECESSARY)

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return copy(value = normalized + other.normalized)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return copy(value = normalized - other.normalized)
    }

    fun isNegative(): Boolean = value.signum() < 0

    fun isPositive(): Boolean = value.signum() > 0

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "Operação entre moedas diferentes: $currency e ${other.currency}"
        }
    }

    companion object {
        const val SCALE = 2

        fun brl(value: BigDecimal): Money = Money(value, Currency.BRL)

        fun zero(currency: Currency = Currency.BRL): Money =
            Money(BigDecimal.ZERO.setScale(SCALE), currency)
    }
}
