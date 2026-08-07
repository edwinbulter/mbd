package com.mbd.portfolio.service

import com.mbd.portfolio.entity.Holding
import com.mbd.portfolio.repository.HoldingRepository
import com.mbd.shared.dto.FundPriceUpdate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
class FundPriceConsumer(
    private val holdingRepository: HoldingRepository,
    private val portfolioService: PortfolioService
) {
    @KafkaListener(topics = ["fund-price-updates"])
    fun handlePriceUpdate(update: FundPriceUpdate) {
        val holdings = holdingRepository.findByFundId(update.fundId)
        holdings.forEach { holding ->
            val updatedHolding = holding.copy(
                currentValue = holding.quantity.multiply(update.newPrice),
                updatedAt = LocalDateTime.now()
            )
            holdingRepository.save(updatedHolding)
        }
        portfolioService.publishPortfolioUpdates(holdings)
    }
}
