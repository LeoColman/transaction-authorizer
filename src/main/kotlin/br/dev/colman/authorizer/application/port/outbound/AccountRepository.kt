package br.dev.colman.authorizer.application.port.outbound

import br.dev.colman.authorizer.domain.Account
import java.math.BigDecimal
import java.util.UUID

interface AccountRepository {

    /**
     * Insere contas novas ignorando ids já existentes (idempotente).
     *
     * @return quantidade de contas efetivamente inseridas.
     */
    fun insertAll(accounts: List<Account>): Int

    fun findById(id: UUID): Account?

    /**
     * Soma [amount] ao saldo de forma atômica.
     *
     * @return novo saldo, ou null se a conta não existe.
     */
    fun applyCredit(accountId: UUID, amount: BigDecimal): BigDecimal?

    /**
     * Subtrai [amount] do saldo de forma atômica, somente se o saldo resultante
     * não ficar negativo (invariante garantida pelo banco, não por leitura prévia).
     *
     * @return novo saldo, ou null se a conta não existe ou o saldo é insuficiente.
     */
    fun applyDebit(accountId: UUID, amount: BigDecimal): BigDecimal?

    /**
     * Saldo atual da conta, ou null se a conta não existe.
     */
    fun currentBalance(accountId: UUID): BigDecimal?
}
