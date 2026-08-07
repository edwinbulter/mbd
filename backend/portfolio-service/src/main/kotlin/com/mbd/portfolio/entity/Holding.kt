package com.mbd.portfolio.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "holdings", uniqueConstraints = [UniqueConstraint(columnNames = ["account_id", "fund_id"])])
class Holding(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    
    @Column(name = "account_id", nullable = false)
    var accountId: Long,
    
    @Column(name = "fund_id", nullable = false)
    var fundId: Long,
    
    @Column(nullable = false, precision = 19, scale = 4)
    var quantity: BigDecimal,
    
    @Column(name = "average_price", nullable = false, precision = 19, scale = 2)
    var averagePrice: BigDecimal,
    
    @Column(name = "current_value", nullable = false, precision = 19, scale = 2)
    var currentValue: BigDecimal,
    
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
