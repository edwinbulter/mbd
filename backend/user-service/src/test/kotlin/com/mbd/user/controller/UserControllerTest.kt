package com.mbd.user.controller

import com.mbd.shared.dto.RegistrationDto
import com.mbd.user.entity.User
import com.mbd.user.repository.UserRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.Base64
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class UserControllerTest {

    @Mock private lateinit var userRepository: UserRepository

    @InjectMocks private lateinit var controller: UserController

    private val user = User(id = 1, keycloakId = "kc-123", email = "test@test.com", firstName = "Test", lastName = "User", role = "user")

    private fun createJwt(sub: String): String {
        val header = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"RS256\"}".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"$sub\"}".toByteArray())
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString("sig".toByteArray())
        return "Bearer $header.$payload.$signature"
    }

    @Test
    fun `register new user saves and returns dto`() {
        val registration = RegistrationDto(keycloakId = "kc-new", email = "new@test.com", firstName = "New", lastName = "User")
        whenever(userRepository.findByKeycloakId("kc-new")).thenReturn(null)
        whenever(userRepository.findByEmail("new@test.com")).thenReturn(null)
        whenever(userRepository.save(any<User>())).thenAnswer { it.arguments[0] }

        val result = controller.register(registration)

        assertEquals(200, result.statusCode.value())
        assertEquals("kc-new", result.body!!.keycloakId)
        assertEquals("new@test.com", result.body!!.email)
        verify(userRepository).save(any())
    }

    @Test
    fun `register duplicate keycloakId returns badRequest`() {
        val registration = RegistrationDto(keycloakId = "kc-123", email = "other@test.com", firstName = "X", lastName = "Y")
        whenever(userRepository.findByKeycloakId("kc-123")).thenReturn(user)

        val result = controller.register(registration)

        assertEquals(400, result.statusCode.value())
        verify(userRepository, never()).save(any())
    }

    @Test
    fun `register duplicate email returns badRequest`() {
        val registration = RegistrationDto(keycloakId = "kc-other", email = "test@test.com", firstName = "X", lastName = "Y")
        whenever(userRepository.findByKeycloakId("kc-other")).thenReturn(null)
        whenever(userRepository.findByEmail("test@test.com")).thenReturn(user)

        val result = controller.register(registration)

        assertEquals(400, result.statusCode.value())
        verify(userRepository, never()).save(any())
    }

    @Test
    fun `getProfile valid jwt returns user`() {
        whenever(userRepository.findByKeycloakId("kc-123")).thenReturn(user)

        val result = controller.getProfile(createJwt("kc-123"))

        assertEquals(200, result.statusCode.value())
        assertEquals("test@test.com", result.body!!.email)
    }

    @Test
    fun `getProfile user not found returns 404`() {
        whenever(userRepository.findByKeycloakId("kc-unknown")).thenReturn(null)

        val result = controller.getProfile(createJwt("kc-unknown"))

        assertEquals(404, result.statusCode.value())
    }

    @Test
    fun `getProfile invalid jwt format throws exception`() {
        assertThrows(IllegalArgumentException::class.java) {
            controller.getProfile("Bearer invalid")
        }
    }

    @Test
    fun `getUserById found returns dto`() {
        whenever(userRepository.findById(1)).thenReturn(Optional.of(user))

        val result = controller.getUserById(1)

        assertEquals(200, result.statusCode.value())
        assertEquals("test@test.com", result.body!!.email)
    }

    @Test
    fun `getUserById not found returns 404`() {
        whenever(userRepository.findById(99)).thenReturn(Optional.empty())

        val result = controller.getUserById(99)

        assertEquals(404, result.statusCode.value())
    }
}
