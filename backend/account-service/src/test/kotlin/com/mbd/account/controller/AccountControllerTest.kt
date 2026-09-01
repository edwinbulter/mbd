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
    fun `createAccount invalid user throws unauthorized`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(null)

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.createAccount(CreateAccountDto(userId = 1), "Bearer token")
        }
        verify(accountRepository, never()).save(any())
    }

    @Test
    fun `createAccount mismatched userId throws forbidden`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.createAccount(CreateAccountDto(userId = 999), "Bearer token")
        }
        verify(accountRepository, never()).save(any())
    }

    @Test
    fun `deposit positive amount records deposit transaction`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(1)).thenReturn(Optional.of(account))
        whenever(accountRepository.save(any<Account>())).thenAnswer { it.arguments[0] }

        val result = controller.deposit(1, DepositDto(BigDecimal("500.00")), "Bearer token")

        assertEquals(200, result.statusCode.value())
        assertEquals(BigDecimal("1500.00"), result.body!!.balance)

        val txCaptor = argumentCaptor<Transaction>()
        verify(transactionRepository).save(txCaptor.capture())
        assertEquals("DEPOSIT", txCaptor.firstValue.type)
        assertEquals("Deposit", txCaptor.firstValue.description)
    }

    @Test
    fun `deposit exceeding maximum limit throws bad request`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(1)).thenReturn(Optional.of(account))

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.deposit(1, DepositDto(BigDecimal("200000.00")), "Bearer token")
        }
        verify(transactionRepository, never()).save(any())
    }

    @Test
    fun `withdrawal exceeding maximum limit throws bad request`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(1)).thenReturn(Optional.of(account))

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.deposit(1, DepositDto(BigDecimal("-100000.00")), "Bearer token")
        }
        verify(transactionRepository, never()).save(any())
    }

    @Test
    fun `deposit below minimum limit throws bad request`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(1)).thenReturn(Optional.of(account))

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.deposit(1, DepositDto(BigDecimal("0.001")), "Bearer token")
        }
        verify(transactionRepository, never()).save(any())
    }

    @Test
    fun `deposit account not found throws 404`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(99)).thenReturn(Optional.empty())

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.deposit(99, DepositDto(BigDecimal("500.00")), "Bearer token")
        }
        verify(transactionRepository, never()).save(any())
    }

    @Test
    fun `deposit unauthorized user throws forbidden`() {
        val otherAccount = Account(id = 2, userId = 999, accountNumber = "MBD002", balance = BigDecimal("1000.00"))
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(2)).thenReturn(Optional.of(otherAccount))

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.deposit(2, DepositDto(BigDecimal("500.00")), "Bearer token")
        }
        verify(transactionRepository, never()).save(any())
    }

    @Test
    fun `getAccount found returns dto`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(1)).thenReturn(Optional.of(account))

        val result = controller.getAccount(1, "Bearer token")

        assertEquals(200, result.statusCode.value())
        assertEquals("MBD001", result.body!!.accountNumber)
    }

    @Test
    fun `getAccount not found throws 404`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(99)).thenReturn(Optional.empty())

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.getAccount(99, "Bearer token")
        }
    }

    @Test
    fun `getAccount unauthorized user throws forbidden`() {
        val otherAccount = Account(id = 2, userId = 999, accountNumber = "MBD002", balance = BigDecimal("1000.00"))
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(2)).thenReturn(Optional.of(otherAccount))

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.getAccount(2, "Bearer token")
        }
    }

    @Test
    fun `getAccountsByUser returns accounts`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findByUserId(1)).thenReturn(listOf(account))

        val result = controller.getAccountsByUser(1, "Bearer token")

        assertEquals(200, result.statusCode.value())
        assertEquals(1, result.body!!.size)
        assertEquals("MBD001", result.body!![0].accountNumber)
    }

    @Test
    fun `getAccountsByUser unauthorized user throws forbidden`() {
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.getAccountsByUser(999, "Bearer token")
        }
    }

    @Test
    fun `getTransactions returns transactions for account`() {
        val tx = Transaction(id = 1, accountId = 1, amount = BigDecimal("500.00"), type = "DEPOSIT", description = "Deposit")
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(1)).thenReturn(Optional.of(account))
        whenever(transactionRepository.findByAccountId(1)).thenReturn(listOf(tx))

        val result = controller.getTransactions(1, "Bearer token")

        assertEquals(200, result.statusCode.value())
        assertEquals(1, result.body!!.size)
        assertEquals("DEPOSIT", result.body!![0].type)
    }

    @Test
    fun `getTransactions unauthorized user throws forbidden`() {
        val otherAccount = Account(id = 2, userId = 999, accountNumber = "MBD002", balance = BigDecimal("1000.00"))
        whenever(userClient.getUserProfile("Bearer token")).thenReturn(user)
        whenever(accountRepository.findById(2)).thenReturn(Optional.of(otherAccount))

        assertThrows(org.springframework.web.server.ResponseStatusException::class.java) {
            controller.getTransactions(2, "Bearer token")
        }
    }
}
