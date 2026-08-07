package com.mbd.portfolio.controller

import com.mbd.portfolio.service.PortfolioService
import com.mbd.shared.dto.PortfolioDto
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
}
