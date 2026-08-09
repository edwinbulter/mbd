package com.mbd.fund.service

import com.mbd.fund.repository.FundRepository
import com.mbd.shared.dto.FundConfigDto
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service

@Service
class ConfigUpdateConsumer(
    private val fundRepository: FundRepository
) {
    @KafkaListener(topics = ["config-updates"], groupId = "fund-service")
    fun handleConfigUpdate(config: FundConfigDto) {
        val funds = fundRepository.findAll()
        funds.forEach { fund ->
            fund.volatility = config.volatility
            fund.updateFrequencyMinutes = config.updateFrequencyMinutes
            fund.updatedAt = java.time.LocalDateTime.now()
            fundRepository.save(fund)
        }
    }
}
