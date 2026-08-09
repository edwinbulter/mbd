package com.mbd.portfolio.service

import com.mbd.portfolio.client.AccountClient
import com.mbd.portfolio.client.FundClient
import com.mbd.portfolio.entity.Holding
import com.mbd.portfolio.repository.HoldingRepository
import com.mbd.portfolio.repository.PortfolioValueSnapshotRepository
import com.mbd.shared.dto.DepositDto
import com.mbd.shared.dto.HoldingDto
import com.mbd.shared.dto.PortfolioDto
import com.mbd.shared.dto.PortfolioValueSnapshotDto
import com.mbd.shared.dto.TradeDto
import org.springframework.data.domain.PageRequest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
class PortfolioService(
    private val holdingRepository: HoldingRepository,
    private val accountClient: AccountClient,
    private val fundClient: FundClient,
    private val snapshotRepository: PortfolioValueSnapshotRepository
) {
    fun getPortfolio(accountId: Long): PortfolioDto {
        val holdings = holdingRepository.findByAccountId(accountId)
        val holdingsWithDetails = holdings.map { holding ->
            val fund = fundClient.getFund(holding.fundId)
            toDto(holding, fund?.name ?: "Unknown", fund?.isin ?: "Unknown")
        }
        val totalValue = holdingsWithDetails.sumOf { it.currentValue }
        return PortfolioDto(accountId, holdingsWithDetails, totalValue)
    }

    @Transactional
    fun executeTrade(trade: TradeDto): HoldingDto {
        if (trade.type != "BUY") {
            throw IllegalArgumentException("Only BUY trades are supported in MVP")
        }

        // 1. Validate fund and get price
        val fund = fundClient.getFund(trade.fundId) ?: throw IllegalArgumentException("Fund not found")
        val totalCost = fund.currentPrice.multiply(trade.quantity)

        // 2. Validate account balance and deduct
        val account = accountClient.getAccount(trade.accountId) ?: throw IllegalArgumentException("Account not found")
        if (account.balance < totalCost) {
            throw IllegalStateException("Insufficient balance")
        }

        // 3. Deduct balance from account (using deposit with negative amount)
        accountClient.updateBalance(trade.accountId, DepositDto(totalCost.negate()))

        // 4. Create or update holding
        var holding = holdingRepository.findByAccountIdAndFundId(trade.accountId, trade.fundId)
        if (holding == null) {
            holding = Holding(
                accountId = trade.accountId,
                fundId = trade.fundId,
                quantity = trade.quantity,
                averagePrice = fund.currentPrice,
                currentValue = totalCost,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now()
            )
        } else {
            val newTotalQuantity = holding.quantity.add(trade.quantity)
            val newTotalCost = holding.quantity.multiply(holding.averagePrice).add(totalCost)
            holding.averagePrice = newTotalCost.divide(newTotalQuantity, 4, java.math.RoundingMode.HALF_UP)
            holding.quantity = newTotalQuantity
            holding.currentValue = newTotalQuantity.multiply(fund.currentPrice)
            holding.updatedAt = LocalDateTime.now()
        }

        val savedHolding = holdingRepository.save(holding)
        return toDto(savedHolding, fund.name, fund.isin)
    }
    
    fun getPortfolioHistory(accountId: Long, limit: Int = 50): List<PortfolioValueSnapshotDto> {
        val snapshots = snapshotRepository.findByAccountIdOrderByTimestampDesc(accountId, PageRequest.of(0, limit))
        return snapshots.reversed().map { snapshot ->
            PortfolioValueSnapshotDto(
                id = snapshot.id,
                accountId = snapshot.accountId,
                totalValue = snapshot.totalValue,
                timestamp = snapshot.timestamp
            )
        }
    }

    fun publishPortfolioUpdates(holdings: List<Holding>) {
        // Publish portfolio updates to Kafka topic if needed
        // This could be used for real-time updates to frontends
    }
    
    private fun toDto(holding: Holding, fundName: String, fundIsin: String): HoldingDto {
        return HoldingDto(
            id = holding.id,
            accountId = holding.accountId,
            fundId = holding.fundId,
            fundName = fundName,
            fundIsin = fundIsin,
            quantity = holding.quantity,
            averagePrice = holding.averagePrice,
            currentValue = holding.currentValue,
            createdAt = holding.createdAt,
            updatedAt = holding.updatedAt
        )
    }
}
