package com.mbd.shared.dto

import java.math.BigDecimal
import java.time.LocalDateTime

data class PortfolioDto(
    val accountId: Long,
    val holdings: List<HoldingDto>,
    val totalValue: BigDecimal
)

data class TradeDto(
    val accountId: Long,
    val fundId: Long,
    val quantity: BigDecimal,
    val price: BigDecimal? = null,  // Optional - portfolio-service fetches current price from fund-service
    val type: String // BUY or SELL
)

data class HoldingDto(
    val id: Long? = null,
    val accountId: Long,
    val fundId: Long,
    val fundName: String,
    val fundIsin: String,
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val currentValue: BigDecimal,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)
