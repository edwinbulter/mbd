package com.mbd.admin.controller

import com.mbd.shared.dto.UserDto
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/monitoring")
class MonitoringController {
    @GetMapping("/system-health")
    @PreAuthorize("hasRole('admin')")
    fun getSystemHealth(): ResponseEntity<Map<String, String>> {
        return ResponseEntity.ok(
            mapOf(
                "status" to "healthy",
                "timestamp" to java.time.LocalDateTime.now().toString()
            )
        )
    }
    
    @GetMapping("/active-users")
    @PreAuthorize("hasRole('admin')")
    fun getActiveUsers(): ResponseEntity<List<UserDto>> {
        // This would typically query the user-service
        // For now, return empty list
        return ResponseEntity.ok(emptyList())
    }
}
