package br.dev.colman.authorizer.application

import br.dev.colman.authorizer.application.port.inbound.NewAccount
import br.dev.colman.authorizer.application.port.outbound.AccountRepository
import br.dev.colman.authorizer.domain.Account
import br.dev.colman.authorizer.domain.AccountStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class AccountRegistrationServiceSpec : FunSpec({

    lateinit var repository: AccountRepository
    lateinit var service: AccountRegistrationService

    beforeTest {
        repository = mockk()
        service = AccountRegistrationService(repository)
    }

    test("lista vazia retorna 0 sem tocar o repositório") {
        service.register(emptyList()) shouldBe 0

        verify(exactly = 0) { repository.insertAll(any()) }
    }

    test("contas novas nascem com saldo ZERO e devolve a contagem de inseridas") {
        val newAccount = NewAccount(
            id = UUID.randomUUID(),
            ownerId = UUID.randomUUID(),
            status = AccountStatus.ENABLED,
            createdAt = Instant.parse("2021-10-22T04:25:39Z"),
        )
        val captured = slot<List<Account>>()
        every { repository.insertAll(capture(captured)) } returns 1

        service.register(listOf(newAccount)) shouldBe 1

        val account = captured.captured.single()
        account.id shouldBe newAccount.id
        account.ownerId shouldBe newAccount.ownerId
        account.status shouldBe AccountStatus.ENABLED
        account.createdAt shouldBe newAccount.createdAt
        account.balance.value.compareTo(BigDecimal.ZERO) shouldBe 0
    }
})
