package com.mbd.user.repository

import com.mbd.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserRepository : JpaRepository<User, Long> {
    fun findByKeycloakId(keycloakId: String): User?
    fun findByEmail(email: String): User?
}
