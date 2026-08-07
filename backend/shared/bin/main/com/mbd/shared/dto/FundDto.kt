package com.mbd.shared.dto

import java.math.BigDecimal
import java.time.LocalDateTime

data class FundDto(
    val id: Long? = null,
    val name: String,
    val isin: String,
    val currentPrice: BigDecimal,
    val currency: String = "EUR",
    val volatility: Double = 0.02,
    val updateFrequencyMinutes: Int = 5,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

data class FundConfigDto(
    val volatility: Double,
    val updateFrequencyMinutes: Int
)

data class FundPriceUpdate(
    val fundId: Long,
    val newPrice: BigDecimal,
    val timestamp: LocalDateTime = java.time.LocalDateTime.now()
)
