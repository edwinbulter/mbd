package com.mbd.portfolio.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "holdings", uniqueConstraints = [UniqueConstraint(columnNames = ["account_id", "fund_id"])])
data class Holding(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,
    
    @Column(name = "fund_id", nullable = false)
    val fundId: Long,
    
    @Column(nullable = false, precision = 19, scale = 4)
    val quantity: BigDecimal,
    
    @Column(name = "average_price", nullable = false, precision = 19, scale = 2)
    val averagePrice: BigDecimal,
    
    @Column(name = "current_value", nullable = false, precision = 19, scale = 2)
    val currentValue: BigDecimal,
    
    @Column(name = "created_at", updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
