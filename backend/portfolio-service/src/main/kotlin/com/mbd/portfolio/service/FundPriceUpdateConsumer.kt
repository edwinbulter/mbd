package com.mbd.portfolio.service

import com.mbd.portfolio.entity.PortfolioValueSnapshot
import com.mbd.portfolio.repository.HoldingRepository
import com.mbd.portfolio.repository.PortfolioValueSnapshotRepository
import com.mbd.shared.dto.FundPriceUpdate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
class FundPriceUpdateConsumer(
    private val holdingRepository: HoldingRepository,
    private val snapshotRepository: PortfolioValueSnapshotRepository
) {
    @KafkaListener(topics = ["fund-price-updates"], groupId = "portfolio-service")
    @Transactional
    fun handlePriceUpdate(update: FundPriceUpdate) {
        val holdings = holdingRepository.findByFundId(update.fundId)
        if (holdings.isEmpty()) return

        val affectedAccountIds = mutableSetOf<Long>()

        holdings.forEach { holding ->
            holding.currentValue = holding.quantity.multiply(update.newPrice)
            holding.updatedAt = LocalDateTime.now()
            holdingRepository.save(holding)
            affectedAccountIds.add(holding.accountId)
        }

        affectedAccountIds.forEach { accountId ->
            val allHoldings = holdingRepository.findByAccountId(accountId)
            val totalValue = allHoldings.sumOf { it.currentValue }
            val snapshot = PortfolioValueSnapshot(
                accountId = accountId,
                totalValue = totalValue,
                timestamp = update.timestamp
            )
            snapshotRepository.save(snapshot)
        }
    }
}
