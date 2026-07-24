package br.dev.colman.authorizer.domain

import java.util.UUID

sealed class DomainException(message: String) : RuntimeException(message)

/** A conta nunca foi informada pelo processo de abertura de contas. */
class AccountNotFoundException(val accountId: UUID) :
    DomainException("Conta não encontrada: $accountId")

/** A conta existe, mas não está habilitada para transacionar. */
class AccountDisabledException(val accountId: UUID) :
    DomainException("Conta desabilitada: $accountId")

/** Já existe uma autorização registrada para este transactionId. */
class DuplicateTransactionException(val transactionId: UUID) :
    DomainException("Transação já autorizada: $transactionId")

/** Moeda não suportada pelo autorizador (ADR-0003: somente BRL). */
class UnsupportedCurrencyException(val currencyCode: String) :
    DomainException("Moeda não suportada: $currencyCode")
