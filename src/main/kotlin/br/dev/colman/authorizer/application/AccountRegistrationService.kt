package br.dev.colman.authorizer.application

import br.dev.colman.authorizer.application.port.inbound.NewAccount
import br.dev.colman.authorizer.application.port.inbound.RegisterAccountsUseCase
import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.domain.Account
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountRegistrationService(
    private val accounts: AccountRepository,
) : RegisterAccountsUseCase {

    @Transactional
    override fun register(accounts: List<NewAccount>): Int {
        if (accounts.isEmpty()) return 0
        val newAccounts = accounts.map { Account.opened(it.id, it.ownerId, it.status, it.createdAt) }
        return this.accounts.insertAll(newAccounts)
    }
}
