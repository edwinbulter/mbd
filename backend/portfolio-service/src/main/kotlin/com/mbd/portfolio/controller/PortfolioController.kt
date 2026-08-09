package com.mbd.portfolio.controller

import com.mbd.portfolio.service.PortfolioService
import com.mbd.shared.dto.HoldingDto
import com.mbd.shared.dto.PortfolioDto
import com.mbd.shared.dto.PortfolioValueSnapshotDto
import com.mbd.shared.dto.TradeDto
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(
    private val portfolioService: PortfolioService
) {
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
        val holding = portfolioService.executeTrade(trade)
        return ResponseEntity.ok(holding)
    }
}
