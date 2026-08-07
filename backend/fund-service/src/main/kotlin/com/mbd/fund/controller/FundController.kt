package com.mbd.fund.controller

import com.mbd.fund.entity.Fund
import com.mbd.fund.repository.FundRepository
import com.mbd.shared.dto.FundConfigDto
import com.mbd.shared.dto.FundDto
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/funds")
class FundController(
    private val fundRepository: FundRepository
) {
    @PostMapping
    fun createFund(@RequestBody fundDto: FundDto): ResponseEntity<FundDto> {
        val fund = Fund(
            name = fundDto.name,
            isin = fundDto.isin,
            currentPrice = fundDto.currentPrice,
            currency = fundDto.currency,
            volatility = fundDto.volatility,
            updateFrequencyMinutes = fundDto.updateFrequencyMinutes
        )
        val savedFund = fundRepository.save(fund)
        return ResponseEntity.ok(toDto(savedFund))
    }
    
    @GetMapping
    fun listFunds(): ResponseEntity<List<FundDto>> {
        val funds = fundRepository.findAll()
        return ResponseEntity.ok(funds.map { toDto(it) })
    }
    
    @GetMapping("/{fundId}")
    fun getFund(@PathVariable fundId: Long): ResponseEntity<FundDto> {
        val fund = fundRepository.findById(fundId)
        return if (fund.isPresent) {
            ResponseEntity.ok(toDto(fund.get()))
        } else {
            ResponseEntity.notFound().build()
        }
    }
    
    @PutMapping("/{fundId}")
    fun updateFund(@PathVariable fundId: Long, @RequestBody fundDto: FundDto): ResponseEntity<FundDto> {
        val existingFund = fundRepository.findById(fundId)
            ?: return ResponseEntity.notFound().build()
        
        val updatedFund = existingFund.copy(
            name = fundDto.name,
            isin = fundDto.isin,
            currentPrice = fundDto.currentPrice,
            currency = fundDto.currency,
            volatility = fundDto.volatility,
            updateFrequencyMinutes = fundDto.updateFrequencyMinutes,
            updatedAt = LocalDateTime.now()
        )
        
        val savedFund = fundRepository.save(updatedFund)
        return ResponseEntity.ok(toDto(savedFund))
    }
    
    @DeleteMapping("/{fundId}")
    fun deleteFund(@PathVariable fundId: Long): ResponseEntity<Void> {
        if (!fundRepository.existsById(fundId)) {
            return ResponseEntity.notFound().build()
        }
        fundRepository.deleteById(fundId)
        return ResponseEntity.noContent().build()
    }
    
    @PutMapping("/{fundId}/config")
    fun updateConfig(@PathVariable fundId: Long, @RequestBody config: FundConfigDto): ResponseEntity<FundDto> {
        val existingFund = fundRepository.findById(fundId)
            ?: return ResponseEntity.notFound().build()
        
        val updatedFund = existingFund.copy(
            volatility = config.volatility,
            updateFrequencyMinutes = config.updateFrequencyMinutes,
            updatedAt = LocalDateTime.now()
        )
        
        val savedFund = fundRepository.save(updatedFund)
        return ResponseEntity.ok(toDto(savedFund))
    }
    
    private fun toDto(fund: Fund): FundDto {
        return FundDto(
            id = fund.id,
            name = fund.name,
            isin = fund.isin,
            currentPrice = fund.currentPrice,
            currency = fund.currency,
            volatility = fund.volatility,
            updateFrequencyMinutes = fund.updateFrequencyMinutes,
            createdAt = fund.createdAt,
            updatedAt = fund.updatedAt
        )
    }
}
