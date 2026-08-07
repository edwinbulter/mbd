package com.mbd.portfolio.service

import com.mbd.portfolio.entity.Holding
import com.mbd.portfolio.repository.HoldingRepository
import com.mbd.shared.dto.HoldingDto
import com.mbd.shared.dto.PortfolioDto
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class PortfolioService(
    private val holdingRepository: HoldingRepository
) {
    fun getPortfolio(accountId: Long): PortfolioDto {
        val holdings = holdingRepository.findByAccountId(accountId)
        val totalValue = holdings.sumOf { it.currentValue }
        return PortfolioDto(accountId, holdings.map { toDto(it) }, totalValue)
    }
    
    fun publishPortfolioUpdates(holdings: List<Holding>) {
        // Publish portfolio updates to Kafka topic if needed
        // This could be used for real-time updates to frontends
    }
    
    private fun toDto(holding: Holding): HoldingDto {
        return HoldingDto(
            id = holding.id,
            accountId = holding.accountId,
            fundId = holding.fundId,
            fundName = "", // Would need to fetch from fund-service
            fundIsin = "", // Would need to fetch from fund-service
            quantity = holding.quantity,
            averagePrice = holding.averagePrice,
            currentValue = holding.currentValue,
            createdAt = holding.createdAt,
            updatedAt = holding.updatedAt
        )
    }
}
