package br.dev.colman.authorizer.adapter.inbound.web.dto

import br.dev.colman.authorizer.application.port.inbound.AuthorizeTransactionCommand
import br.dev.colman.authorizer.domain.Currency
import br.dev.colman.authorizer.domain.Money
import br.dev.colman.authorizer.domain.TransactionType
import br.dev.colman.authorizer.domain.UnsupportedCurrencyException
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
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
    fun toCommand(transactionId: UUID): AuthorizeTransactionCommand = AuthorizeTransactionCommand(
        transactionId = transactionId,
        accountId = requireNotNull(accountId),
        type = requireNotNull(type),
        amount = Money(requireNotNull(amount?.value), parseCurrency(requireNotNull(amount?.currency))),
    )

    private fun parseCurrency(code: String): Currency =
        runCatching { Currency.valueOf(code) }.getOrElse { throw UnsupportedCurrencyException(code) }
}

data class AmountRequest(
    @field:NotNull(message = "amount.value é obrigatório")
    @field:DecimalMin(value = "0.01", message = "amount.value deve ser no mínimo 0.01")
    @field:Digits(integer = 15, fraction = 2, message = "amount.value deve ter no máximo 15 dígitos inteiros e 2 casas decimais")
    val value: BigDecimal?,

    // Sem validação de formato aqui: qualquer código fora do enum de moedas
    // suportadas responde 422 unsupported-currency, nunca 400 genérico.
    @field:NotNull(message = "amount.currency é obrigatório")
    val currency: String?,
)
