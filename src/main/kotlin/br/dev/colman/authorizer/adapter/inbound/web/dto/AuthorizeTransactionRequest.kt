package br.dev.colman.authorizer.adapter.inbound.web.dto

import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionCommand
import br.dev.colman.authorizer.domain.Currency
import br.dev.colman.authorizer.domain.Money
import br.dev.colman.authorizer.domain.TransactionType
import br.dev.colman.authorizer.domain.UnsupportedCurrencyException
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

data class AuthorizeTransactionRequest(
    @field:NotNull(message = "accountId é obrigatório")
    val accountId: UUID?,

    @field:NotNull(message = "type é obrigatório (CREDIT ou DEBIT)")
    val type: TransactionType?,

    @field:NotNull(message = "amount é obrigatório")
    @field:Valid
    val amount: AmountRequest?,
) {
    fun toCommand(transactionId: UUID): AuthorizeTransactionCommand {
        val amount = requireNotNull(amount)
        return AuthorizeTransactionCommand(
            transactionId = transactionId,
            accountId = requireNotNull(accountId),
            type = requireNotNull(type),
            amount = Money(requireNotNull(amount.value), parseCurrency(requireNotNull(amount.currency))),
        )
    }

    private fun parseCurrency(code: String): Currency =
        runCatching { Currency.valueOf(code) }.getOrElse { throw UnsupportedCurrencyException(code) }
}

data class AmountRequest(
    // DecimalMax compara por valor (compareTo, à prova de overflow) e barra
    // notação exponencial com escala absurda que passaria pelo @Digits (cujo
    // cálculo precision - scale estoura int) e quebraria a normalização.
    @field:NotNull(message = "amount.value é obrigatório")
    @field:DecimalMin(value = "0.01", message = "amount.value deve ser no mínimo 0.01")
    @field:DecimalMax(value = "99999999999999999.99", message = "amount.value excede o valor máximo suportado")
    @field:Digits(integer = 15, fraction = 2, message = "amount.value deve ter no máximo 15 dígitos inteiros e 2 casas decimais")
    val value: BigDecimal?,

    // Só o tamanho é validado aqui (contém o dano de payloads gigantes sem
    // refletir o valor); qualquer código de 3 caracteres fora do enum de
    // moedas suportadas responde 422 unsupported-currency, nunca 400 genérico.
    @field:NotNull(message = "amount.currency é obrigatório")
    @field:Size(min = 3, max = 3, message = "amount.currency deve ter 3 caracteres (ISO 4217)")
    val currency: String?,
)
