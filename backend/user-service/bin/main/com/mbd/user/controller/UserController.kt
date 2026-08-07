package com.mbd.user.controller

import com.mbd.shared.dto.RegistrationDto
import com.mbd.shared.dto.UserDto
import com.mbd.user.entity.User
import com.mbd.user.repository.UserRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userRepository: UserRepository
) {
    @GetMapping("/profile")
    fun getProfile(@RequestHeader("Authorization") authHeader: String): ResponseEntity<UserDto> {
        // Extract keycloakId from JWT token (simplified - in production, validate JWT properly)
        val keycloakId = extractKeycloakIdFromToken(authHeader)
        
        val user = userRepository.findByKeycloakId(keycloakId)
            ?: return ResponseEntity.notFound().build()
        
        return ResponseEntity.ok(toDto(user))
    }
    
    @PostMapping("/register")
    fun register(@RequestBody registrationDto: RegistrationDto): ResponseEntity<UserDto> {
        // Check if user already exists
        if (userRepository.findByKeycloakId(registrationDto.keycloakId) != null) {
            return ResponseEntity.badRequest().build()
        }
        
        if (userRepository.findByEmail(registrationDto.email) != null) {
            return ResponseEntity.badRequest().build()
        }
        
        val user = User(
            keycloakId = registrationDto.keycloakId,
            email = registrationDto.email,
            firstName = registrationDto.firstName,
            lastName = registrationDto.lastName,
            role = registrationDto.role
        )
        
        val savedUser = userRepository.save(user)
        return ResponseEntity.ok(toDto(savedUser))
    }
    
    @GetMapping("/{id}")
    fun getUserById(@PathVariable id: Long): ResponseEntity<UserDto> {
        val user = userRepository.findById(id)
        return if (user.isPresent) {
            ResponseEntity.ok(toDto(user.get()))
        } else {
            ResponseEntity.notFound().build()
        }
    }
    
    private fun toDto(user: User): UserDto {
        return UserDto(
            id = user.id,
            keycloakId = user.keycloakId,
            email = user.email,
            firstName = user.firstName,
            lastName = user.lastName,
            role = user.role,
            createdAt = user.createdAt,
            updatedAt = user.updatedAt
        )
    }
    
    private fun extractKeycloakIdFromToken(authHeader: String): String {
        // Simplified token extraction - in production, validate JWT properly with Keycloak
        val token = authHeader.removePrefix("Bearer ")
        // This is a placeholder - implement proper JWT validation
        return "test-keycloak-id"
    }
}
