package br.dev.colman.authorizer.application.port.outbound

import br.dev.colman.authorizer.domain.DuplicateTransactionException
import br.dev.colman.authorizer.domain.Transaction
import java.util.UUID

interface TransactionRepository {

    fun findById(id: UUID): Transaction?

    /**
     * Registra o resultado de uma autorização.
     *
     * @throws DuplicateTransactionException se já existe autorização para o mesmo id.
     */
    fun insert(transaction: Transaction)
}
