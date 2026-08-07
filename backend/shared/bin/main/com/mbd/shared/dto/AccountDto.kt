package com.mbd.shared.dto

import java.math.BigDecimal
import java.time.LocalDateTime

data class AccountDto(
    val id: Long? = null,
    val userId: Long,
    val accountNumber: String,
    val balance: BigDecimal,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

data class CreateAccountDto(
    val userId: Long
)

data class DepositDto(
    val amount: BigDecimal
)
