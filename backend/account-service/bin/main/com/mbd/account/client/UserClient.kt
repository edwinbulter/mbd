package com.mbd.account.client

import com.mbd.shared.dto.UserDto
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader

@FeignClient(name = "user-service", url = "http://user-service.mbd.svc.cluster.local:8080")
interface UserClient {
    @GetMapping("/api/users/profile")
    fun getUserProfile(@RequestHeader("Authorization") authHeader: String): UserDto?
}
