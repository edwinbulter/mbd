package com.mbd.portfolio.controller

import com.mbd.portfolio.service.PortfolioService
import com.mbd.shared.dto.HoldingDto
import com.mbd.shared.dto.PortfolioDto
import com.mbd.shared.dto.PortfolioValueSnapshotDto
import com.mbd.shared.dto.TradeDto
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(
    private val portfolioService: PortfolioService
) {
    companion object {
        // Trade limits per operation (regulatory compliance)
        private val MAX_TRADE_QUANTITY = BigDecimal("10000") // 10,000 shares/units per trade
        private val MIN_TRADE_QUANTITY = BigDecimal("0.01") // Minimum 0.01 shares/units
    }
    @GetMapping("/{accountId}")
    fun getPortfolio(@PathVariable accountId: Long): ResponseEntity<PortfolioDto> {
        val portfolio = portfolioService.getPortfolio(accountId)
        return ResponseEntity.ok(portfolio)
    }

    @GetMapping("/{accountId}/history")
    fun getPortfolioHistory(
        @PathVariable accountId: Long,
        @RequestParam(defaultValue = "50") limit: Int
    ): ResponseEntity<List<PortfolioValueSnapshotDto>> {
        val history = portfolioService.getPortfolioHistory(accountId, limit)
        return ResponseEntity.ok(history)
    }

    @PostMapping("/trade")
    fun executeTrade(@RequestBody trade: TradeDto): ResponseEntity<HoldingDto> {
        // Validate trade quantity limits
        if (trade.quantity < MIN_TRADE_QUANTITY) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Trade quantity must be at least ${MIN_TRADE_QUANTITY}"
            )
        }
        if (trade.quantity > MAX_TRADE_QUANTITY) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Trade quantity exceeds maximum limit of ${MAX_TRADE_QUANTITY.toPlainString()}"
            )
        }

        val holding = portfolioService.executeTrade(trade)
        return ResponseEntity.ok(holding)
    }

    @GetMapping("/config/limits")
    fun getLimits(): ResponseEntity<Map<String, BigDecimal>> {
        return ResponseEntity.ok(mapOf(
            "maxTradeQuantity" to MAX_TRADE_QUANTITY,
            "minTradeQuantity" to MIN_TRADE_QUANTITY
        ))
    }
}
