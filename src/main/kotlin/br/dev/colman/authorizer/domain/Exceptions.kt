package br.dev.colman.authorizer.domain

import java.util.UUID

sealed class DomainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A conta nunca foi informada pelo processo de abertura de contas. */
class AccountNotFoundException(val accountId: UUID) :
    DomainException("Conta não encontrada: $accountId")

/** A conta existe, mas não está habilitada para transacionar. */
class AccountDisabledException(val accountId: UUID) :
    DomainException("Conta desabilitada: $accountId")

/** Já existe uma autorização registrada para este transactionId. */
class DuplicateTransactionException(val transactionId: UUID, cause: Throwable? = null) :
    DomainException("Transação já autorizada: $transactionId", cause)

/** Moeda não suportada pelo autorizador (ADR-0003: somente BRL). */
class UnsupportedCurrencyException(val currencyCode: String) :
    DomainException("Moeda não suportada: $currencyCode")

/**
 * Retentativa com o mesmo transactionId mas payload divergente do original:
 * não é replay legítimo, é erro do cliente (provável bug na geração de ids).
 */
class IdempotencyConflictException(val transactionId: UUID) :
    DomainException("Transação $transactionId já autorizada com payload diferente")
