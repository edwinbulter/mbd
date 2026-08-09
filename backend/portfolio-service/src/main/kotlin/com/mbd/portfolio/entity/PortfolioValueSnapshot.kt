package com.mbd.portfolio.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "portfolio_value_history")
class PortfolioValueSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "account_id", nullable = false)
    var accountId: Long = 0,

    @Column(name = "total_value", nullable = false, precision = 19, scale = 2)
    var totalValue: BigDecimal = BigDecimal.ZERO,

    @Column(name = "timestamp", nullable = false)
    var timestamp: LocalDateTime = LocalDateTime.now()
)
