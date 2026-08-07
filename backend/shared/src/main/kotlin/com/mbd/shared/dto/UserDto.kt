package com.mbd.shared.dto

import java.time.LocalDateTime

data class UserDto(
    val id: Long? = null,
    val keycloakId: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val role: String,
    val createdAt: LocalDateTime? = null,
    val updatedAt: LocalDateTime? = null
)

data class RegistrationDto(
    val keycloakId: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val role: String = "user"
)
