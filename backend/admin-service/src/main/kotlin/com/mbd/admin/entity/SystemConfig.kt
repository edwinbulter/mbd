package com.mbd.admin.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "system_config")
data class SystemConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(unique = true, nullable = false, length = 100)
    val key: String,
    
    @Column(nullable = false, columnDefinition = "TEXT")
    val value: String,
    
    val description: String? = null,
    
    @Column(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
