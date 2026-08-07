package com.mbd.account.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(name = "transactions")
data class Transaction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,
    
    @Column(nullable = false, precision = 19, scale = 2)
    val amount: BigDecimal,
    
    @Column(nullable = false)
    val type: String,
    
    val description: String? = null,
    
    @Column(name = "created_at", updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
