package com.mbd.admin.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "system_config")
class SystemConfig(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    
    @Column(unique = true, nullable = false, length = 100)
    var key: String,
    
    @Column(nullable = false, columnDefinition = "TEXT")
    var value: String,
    
    var description: String? = null,
    
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
