package com.mbd.account.controller

import com.mbd.account.client.UserClient
import com.mbd.account.entity.Account
import com.mbd.account.entity.Transaction
import com.mbd.account.repository.AccountRepository
import com.mbd.account.repository.TransactionRepository
import com.mbd.shared.dto.AccountDto
import com.mbd.shared.dto.CreateAccountDto
import com.mbd.shared.dto.DepositDto
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val userClient: UserClient
) {
    @PostMapping
    fun createAccount(@RequestBody request: CreateAccountDto, @RequestHeader("Authorization") authHeader: String): ResponseEntity<AccountDto> {
        // Validate user exists and get authenticated user
        val user = userClient.getUserProfile(authHeader)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication")

        // Authorization check: Ensure the userId in request matches the authenticated user
        if (request.userId != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot create account for another user")
        }

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
    fun deposit(
        @PathVariable accountId: Long,
        @RequestBody request: DepositDto,
        @RequestHeader(value = "Authorization", required = false) authHeader: String?
    ): ResponseEntity<AccountDto> {
        // Get authenticated user (optional for service-to-service calls)
        val user = authHeader?.let { userClient.getUserProfile(it) }

        // Find the account
        val account = accountRepository.findById(accountId)
            .orElse(null) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")

        // Authorization check: Verify the authenticated user owns this account (only if auth header present)
        if (user != null && account.userId != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to this account")
        }

        // Validate amount is not zero
        if (request.amount.compareTo(BigDecimal.ZERO) == 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount cannot be zero")
        }

        // Update balance
        account.balance = account.balance.add(request.amount)
        account.updatedAt = LocalDateTime.now()

        val savedAccount = accountRepository.save(account)

        // Record transaction with appropriate type
        val transactionType = if (request.amount > BigDecimal.ZERO) "DEPOSIT" else "WITHDRAWAL"
        val description = if (request.amount > BigDecimal.ZERO) "Deposit" else "Withdrawal"

        val transaction = Transaction(
            accountId = accountId,
            amount = request.amount,
            type = transactionType,
            description = description
        )
        transactionRepository.save(transaction)

        return ResponseEntity.ok(toDto(savedAccount))
    }
    
    @GetMapping("/{accountId}")
    fun getAccount(
        @PathVariable accountId: Long,
        @RequestHeader(value = "Authorization", required = false) authHeader: String?
    ): ResponseEntity<AccountDto> {
        // Get authenticated user (optional for service-to-service calls)
        val user = authHeader?.let { userClient.getUserProfile(it) }

        // Find the account
        val account = accountRepository.findById(accountId)
            .orElse(null) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")

        // Authorization check: Verify the authenticated user owns this account (only if auth header present)
        if (user != null && account.userId != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to this account")
        }

        return ResponseEntity.ok(toDto(account))
    }
    
    @GetMapping("/user/{userId}")
    fun getAccountsByUser(
        @PathVariable userId: Long,
        @RequestHeader("Authorization") authHeader: String
    ): ResponseEntity<List<AccountDto>> {
        // Get authenticated user
        val user = userClient.getUserProfile(authHeader)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication")

        // Authorization check: Users can only access their own accounts
        if (userId != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to other user's accounts")
        }

        val accounts = accountRepository.findByUserId(userId)
        return ResponseEntity.ok(accounts.map { toDto(it) })
    }
    
    @GetMapping("/{accountId}/transactions")
    fun getTransactions(
        @PathVariable accountId: Long,
        @RequestHeader("Authorization") authHeader: String
    ): ResponseEntity<List<Transaction>> {
        // Get authenticated user
        val user = userClient.getUserProfile(authHeader)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication")

        // Find the account to verify ownership
        val account = accountRepository.findById(accountId)
            .orElse(null) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")

        // Authorization check: Verify the authenticated user owns this account
        if (account.userId != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to this account's transactions")
        }

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
        val secureRandom = SecureRandom()
        val randomBytes = ByteArray(8)
        secureRandom.nextBytes(randomBytes)

        // Convert to hex string and take first 10 characters
        val hexString = randomBytes.joinToString("") { "%02X".format(it) }
        return "MBD" + hexString.take(10)
    }
}
