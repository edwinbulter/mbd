package com.mbd.fund.service

import com.mbd.fund.entity.Fund
import com.mbd.fund.repository.FundRepository
import com.mbd.shared.dto.FundPriceUpdate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

@Service
class PriceUpdateScheduler(
    private val fundRepository: FundRepository,
    private val priceProducer: FundPriceProducer
) {
    @Scheduled(fixedRate = 300000) // 5 minutes default
    fun updatePrices() {
        val funds = fundRepository.findAll()
        funds.forEach { fund ->
            val newPrice = calculateRandomPrice(fund.currentPrice, fund.volatility)
            fund.currentPrice = newPrice
            fund.updatedAt = java.time.LocalDateTime.now()
            fundRepository.save(fund)
            priceProducer.publishPriceUpdate(FundPriceUpdate(fund.id!!, newPrice))
        }
    }
    
    private fun calculateRandomPrice(currentPrice: BigDecimal, volatility: Double): BigDecimal {
        val randomFactor = Random.nextDouble(-volatility, volatility)
        val change = currentPrice.multiply(BigDecimal(randomFactor))
        return currentPrice.add(change).setScale(2, RoundingMode.HALF_UP)
    }
}
