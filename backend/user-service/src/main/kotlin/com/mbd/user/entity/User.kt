package com.mbd.user.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,
    
    @Column(unique = true, nullable = false)
    var keycloakId: String = "",
    
    @Column(unique = true, nullable = false)
    var email: String = "",
    
    @Column(nullable = false)
    var firstName: String = "",
    
    @Column(nullable = false)
    var lastName: String = "",
    
    @Column(nullable = false)
    var role: String = "",
    
    @Column(name = "created_at", updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),
    
    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
