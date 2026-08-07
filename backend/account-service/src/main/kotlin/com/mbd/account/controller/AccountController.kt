package com.mbd.account.controller

import com.mbd.account.client.UserClient
import com.mbd.account.entity.Account
import com.mbd.account.entity.Transaction
import com.mbd.account.repository.AccountRepository
import com.mbd.account.repository.TransactionRepository
import com.mbd.shared.dto.AccountDto
import com.mbd.shared.dto.CreateAccountDto
import com.mbd.shared.dto.DepositDto
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val userClient: UserClient
) {
    @PostMapping
    fun createAccount(@RequestBody request: CreateAccountDto, @RequestHeader("Authorization") authHeader: String): ResponseEntity<AccountDto> {
        // Validate user exists
        val user = userClient.getUserProfile(authHeader)
            ?: return ResponseEntity.badRequest().build()
        
        // Generate unique account number
        val accountNumber = generateAccountNumber()
        
        val account = Account(
            userId = request.userId,
            accountNumber = accountNumber,
            balance = BigDecimal.ZERO
        )
        
        val savedAccount = accountRepository.save(account)
        return ResponseEntity.ok(toDto(savedAccount))
    }
    
    @PostMapping("/{accountId}/deposit")
    fun deposit(@PathVariable accountId: Long, @RequestBody request: DepositDto): ResponseEntity<AccountDto> {
        val account = accountRepository.findById(accountId)
            ?: return ResponseEntity.notFound().build()
        
        // Update balance
        val updatedAccount = account.copy(
            balance = account.balance.add(request.amount),
            updatedAt = LocalDateTime.now()
        )
        
        val savedAccount = accountRepository.save(updatedAccount)
        
        // Record transaction
        val transaction = Transaction(
            accountId = accountId,
            amount = request.amount,
            type = "DEPOSIT",
            description = "Deposit"
        )
        transactionRepository.save(transaction)
        
        return ResponseEntity.ok(toDto(savedAccount))
    }
    
    @GetMapping("/{accountId}")
    fun getAccount(@PathVariable accountId: Long): ResponseEntity<AccountDto> {
        val account = accountRepository.findById(accountId)
        return if (account.isPresent) {
            ResponseEntity.ok(toDto(account.get()))
        } else {
            ResponseEntity.notFound().build()
        }
    }
    
    @GetMapping("/user/{userId}")
    fun getAccountsByUser(@PathVariable userId: Long): ResponseEntity<List<AccountDto>> {
        val accounts = accountRepository.findByUserId(userId)
        return ResponseEntity.ok(accounts.map { toDto(it) })
    }
    
    @GetMapping("/{accountId}/transactions")
    fun getTransactions(@PathVariable accountId: Long): ResponseEntity<List<Transaction>> {
        val transactions = transactionRepository.findByAccountId(accountId)
        return ResponseEntity.ok(transactions)
    }
    
    private fun toDto(account: Account): AccountDto {
        return AccountDto(
            id = account.id,
            userId = account.userId,
            accountNumber = account.accountNumber,
            balance = account.balance,
            createdAt = account.createdAt,
            updatedAt = account.updatedAt
        )
    }
    
    private fun generateAccountNumber(): String {
        return "MBD" + UUID.randomUUID().toString().takeUpper(10)
    }
}
