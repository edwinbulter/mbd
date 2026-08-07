package com.mbd.user.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "users")
data class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(unique = true, nullable = false)
    val keycloakId: String,
    
    @Column(unique = true, nullable = false)
    val email: String,
    
    @Column(nullable = false)
    val firstName: String,
    
    @Column(nullable = false)
    val lastName: String,
    
    @Column(nullable = false)
    val role: String,
    
    @Column(name = "created_at", updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    val updatedAt: LocalDateTime = LocalDateTime.now()
)
