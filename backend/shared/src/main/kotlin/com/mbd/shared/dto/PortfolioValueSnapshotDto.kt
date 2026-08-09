package com.mbd.shared.dto

import java.math.BigDecimal
import java.time.LocalDateTime

data class PortfolioValueSnapshotDto(
    val id: Long? = null,
    val accountId: Long,
    val totalValue: BigDecimal,
    val timestamp: LocalDateTime
)
