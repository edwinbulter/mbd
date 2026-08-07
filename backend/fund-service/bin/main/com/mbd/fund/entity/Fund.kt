package com.mbd.fund.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "funds")
data class Fund(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(nullable = false)
    val name: String,
    
    @Column(unique = true, nullable = false, length = 12)
    val isin: String,
    
    @Column(nullable = false, precision = 19, scale = 2)
    val currentPrice: BigDecimal,
    
    @Column(length = 3)
    val currency: String = "EUR",
    
    @Column(precision = 5, scale = 4)
    val volatility: Double = 0.02,
    
    @Column(name = "update_frequency_minutes")
    val updateFrequencyMinutes: Int = 5,
    
    @Column(name = "created_at", updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
