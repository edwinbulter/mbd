package com.mbd.account.controller

import com.mbd.account.client.UserClient
import com.mbd.account.entity.Account
import com.mbd.account.entity.Transaction
import com.mbd.account.repository.AccountRepository
import com.mbd.account.repository.TransactionRepository
import com.mbd.shared.dto.CreateAccountDto
import com.mbd.shared.dto.DepositDto
import com.mbd.shared.dto.UserDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class AccountControllerTest {

    @Mock private lateinit var accountRepository: AccountRepository
    @Mock private lateinit var transactionRepository: TransactionRepository
    @Mock private lateinit var userClient: UserClient

    @InjectMocks private lateinit var controller: AccountController

    private val user = UserDto(id = 1, keycloakId = "kc-123", email = "test@test.com", firstName = "Test", lastName = "User", role = "user")

    private val account = Account(id = 1, userId = 1, accountNumber = "MBD001", balance = BigDecimal("1000.00"))

    @Test
    fun `createAccount valid user creates account`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.save(any<Account>())).thenAnswer { it.arguments[0] }

        val result = controller.createAccount(CreateAccountDto(userId = 1), "Bearer token")

        assertEquals(200, result.statusCode.value())
        assertEquals(1L, result.body!!.userId)
        assertEquals(BigDecimal.ZERO, result.body!!.balance)
        verify(accountRepository).save(any())
    }

    @Test
    fun `createAccount invalid user returns badRequest`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(null)

        val result = controller.createAccount(CreateAccountDto(userId = 1), "Bearer token")

        assertEquals(400, result.statusCode.value())
        verify(accountRepository, never()).save(any())
    }

    @Test
    fun `deposit positive amount records deposit transaction`() {
        whenever(accountRepository.findById(1)).thenReturn(Optional.of(account))
        whenever(accountRepository.save(any<Account>())).thenAnswer { it.arguments[0] }

        val result = controller.deposit(1, DepositDto(BigDecimal("500.00")))

        assertEquals(200, result.statusCode.value())
        assertEquals(BigDecimal("1500.00"), result.body!!.balance)

        val txCaptor = argumentCaptor<Transaction>()
        verify(transactionRepository).save(txCaptor.capture())
        assertEquals("DEPOSIT", txCaptor.firstValue.type)
        assertEquals("Deposit", txCaptor.firstValue.description)
    }

    @Test
    fun `deposit negative amount records buy withdrawal transaction`() {
        whenever(accountRepository.findById(1)).thenReturn(Optional.of(account))
        whenever(accountRepository.save(any<Account>())).thenAnswer { it.arguments[0] }

        val result = controller.deposit(1, DepositDto(BigDecimal("-300.00")))

        assertEquals(200, result.statusCode.value())
        assertEquals(BigDecimal("700.00"), result.body!!.balance)

        val txCaptor = argumentCaptor<Transaction>()
        verify(transactionRepository).save(txCaptor.capture())
        assertEquals("BUY_WITHDRAWAL", txCaptor.firstValue.type)
        assertEquals("Buy Order", txCaptor.firstValue.description)
    }

    @Test
    fun `deposit account not found returns 404`() {
        whenever(accountRepository.findById(99)).thenReturn(Optional.empty())

        val result = controller.deposit(99, DepositDto(BigDecimal("500.00")))

        assertEquals(404, result.statusCode.value())
        verify(transactionRepository, never()).save(any())
    }

    @Test
    fun `getAccount found returns dto`() {
        whenever(accountRepository.findById(1)).thenReturn(Optional.of(account))

        val result = controller.getAccount(1)

        assertEquals(200, result.statusCode.value())
        assertEquals("MBD001", result.body!!.accountNumber)
    }

    @Test
    fun `getAccount not found returns 404`() {
        whenever(accountRepository.findById(99)).thenReturn(Optional.empty())

        val result = controller.getAccount(99)

        assertEquals(404, result.statusCode.value())
    }

    @Test
    fun `getAccountsByUser returns accounts`() {
        whenever(accountRepository.findByUserId(1)).thenReturn(listOf(account))

        val result = controller.getAccountsByUser(1)

        assertEquals(200, result.statusCode.value())
        assertEquals(1, result.body!!.size)
        assertEquals("MBD001", result.body!![0].accountNumber)
    }

    @Test
    fun `getTransactions returns transactions for account`() {
        val tx = Transaction(id = 1, accountId = 1, amount = BigDecimal("500.00"), type = "DEPOSIT", description = "Deposit")
        whenever(transactionRepository.findByAccountId(1)).thenReturn(listOf(tx))

        val result = controller.getTransactions(1)

        assertEquals(200, result.statusCode.value())
        assertEquals(1, result.body!!.size)
        assertEquals("DEPOSIT", result.body!![0].type)
    }
}
