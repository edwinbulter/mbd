package com.mbd.admin.controller

import com.mbd.admin.entity.SystemConfig
import com.mbd.admin.repository.SystemConfigRepository
import com.mbd.shared.dto.FundConfigDto
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/admin/config")
class AdminConfigController(
    private val configRepository: SystemConfigRepository
) {
    @GetMapping("/price-update")
    @PreAuthorize("hasRole('employee')")
    fun getPriceUpdateConfig(): ResponseEntity<FundConfigDto> {
        val frequency = configRepository.findByKey("price_update_frequency_minutes")
        val volatility = configRepository.findByKey("price_update_volatility")
        
        return ResponseEntity.ok(
            FundConfigDto(
                volatility = volatility?.value?.toDouble() ?: 0.02,
                updateFrequencyMinutes = frequency?.value?.toInt() ?: 5
            )
        )
    }
    
    @PutMapping("/price-update")
    @PreAuthorize("hasRole('employee')")
    fun updatePriceUpdateConfig(@RequestBody config: FundConfigDto): ResponseEntity<FundConfigDto> {
        val frequency = configRepository.findByKey("price_update_frequency_minutes")
        val volatility = configRepository.findByKey("price_update_volatility")
        
        if (frequency != null) {
            frequency.value = config.updateFrequencyMinutes.toString()
            frequency.updatedAt = LocalDateTime.now()
            configRepository.save(frequency)
        } else {
            configRepository.save(
                SystemConfig(
                    key = "price_update_frequency_minutes",
                    value = config.updateFrequencyMinutes.toString(),
                    description = "Default frequency for fund price updates in minutes"
                )
            )
        }
        
        if (volatility != null) {
            volatility.value = config.volatility.toString()
            volatility.updatedAt = LocalDateTime.now()
            configRepository.save(volatility)
        } else {
            configRepository.save(
                SystemConfig(
                    key = "price_update_volatility",
                    value = config.volatility.toString(),
                    description = "Default volatility for random price generation"
                )
            )
        }
        
        return ResponseEntity.ok(config)
    }
    
    @GetMapping
    @PreAuthorize("hasRole('employee')")
    fun getAllConfigs(): ResponseEntity<List<SystemConfig>> {
        return ResponseEntity.ok(configRepository.findAll())
    }
}
