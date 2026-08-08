package com.mbd.fund.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "funds")
class Fund(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    
    @Column(nullable = false)
    var name: String,
    
    @Column(unique = true, nullable = false, length = 12)
    var isin: String,
    
    @Column(nullable = false, precision = 19, scale = 2)
    var currentPrice: BigDecimal,
    
    @Column(length = 3)
    var currency: String = "EUR",
    
    @Column
    var volatility: Double = 0.02,
    
    @Column(name = "update_frequency_minutes")
    var updateFrequencyMinutes: Int = 5,
    
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
