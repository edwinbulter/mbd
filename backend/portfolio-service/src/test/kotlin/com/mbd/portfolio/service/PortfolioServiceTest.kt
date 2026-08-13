package com.mbd.portfolio.service

import com.mbd.portfolio.client.AccountClient
import com.mbd.portfolio.client.FundClient
import com.mbd.portfolio.entity.Holding
import com.mbd.portfolio.repository.HoldingRepository
import com.mbd.portfolio.repository.PortfolioValueSnapshotRepository
import com.mbd.shared.dto.AccountDto
import com.mbd.shared.dto.DepositDto
import com.mbd.shared.dto.FundDto
import com.mbd.shared.dto.TradeDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal

@ExtendWith(MockitoExtension::class)
class PortfolioServiceTest {

    @Mock private lateinit var holdingRepository: HoldingRepository
    @Mock private lateinit var accountClient: AccountClient
    @Mock private lateinit var fundClient: FundClient
    @Mock private lateinit var snapshotRepository: PortfolioValueSnapshotRepository

    @InjectMocks private lateinit var portfolioService: PortfolioService

    private val fund = FundDto(
        id = 1, name = "Global Equities Fund", isin = "GB0001",
        currentPrice = BigDecimal("100.00")
    )

    private val account = AccountDto(
        id = 1, userId = 10, accountNumber = "MBD001",
        balance = BigDecimal("5000.00")
    )

    // --- BUY tests ---

    @Test
    fun `executeTrade BUY new holding creates holding and deducts balance`() {
        val trade = TradeDto(accountId = 1, fundId = 1, quantity = BigDecimal("5"), price = BigDecimal("100.00"), type = "BUY")
        whenever(fundClient.getFund(1)).thenReturn(fund)
        whenever(accountClient.getAccount(1)).thenReturn(account)
        whenever(holdingRepository.findByAccountIdAndFundId(1, 1)).thenReturn(null)
        whenever(holdingRepository.save(any<Holding>())).thenAnswer { it.arguments[0] }

        val result = portfolioService.executeTrade(trade)

        assertEquals(BigDecimal("5"), result.quantity)
        assertEquals(BigDecimal("100.00"), result.averagePrice)
        assertEquals(BigDecimal("500.00"), result.currentValue)

        val depositCaptor = argumentCaptor<DepositDto>()
        verify(accountClient).updateBalance(eq(1L), depositCaptor.capture())
        assertEquals(BigDecimal("-500.00"), depositCaptor.firstValue.amount)
        verify(holdingRepository).save(any())
    }

    @Test
    fun `executeTrade BUY existing holding updates quantity and recalculates avg price`() {
        val existing = Holding(
            id = 1, accountId = 1, fundId = 1,
            quantity = BigDecimal("10"), averagePrice = BigDecimal("80.00"),
            currentValue = BigDecimal("1000.00")
        )
        val trade = TradeDto(accountId = 1, fundId = 1, quantity = BigDecimal("5"), price = BigDecimal("100.00"), type = "BUY")
        whenever(fundClient.getFund(1)).thenReturn(fund)
        whenever(accountClient.getAccount(1)).thenReturn(account)
        whenever(holdingRepository.findByAccountIdAndFundId(1, 1)).thenReturn(existing)
        whenever(holdingRepository.save(any<Holding>())).thenAnswer { it.arguments[0] }

        val result = portfolioService.executeTrade(trade)

        assertEquals(BigDecimal("15"), result.quantity)
        assertEquals(BigDecimal("86.6667"), result.averagePrice)
        assertEquals(BigDecimal("1500.00"), result.currentValue)
        val depositCaptor = argumentCaptor<DepositDto>()
        verify(accountClient).updateBalance(eq(1L), depositCaptor.capture())
        assertEquals(BigDecimal("-500.00"), depositCaptor.firstValue.amount)
    }

    @Test
    fun `executeTrade BUY insufficient balance throws IllegalStateException`() {
        val poorAccount = account.copy(balance = BigDecimal("100.00"))
        val trade = TradeDto(accountId = 1, fundId = 1, quantity = BigDecimal("5"), price = BigDecimal("100.00"), type = "BUY")
        whenever(fundClient.getFund(1)).thenReturn(fund)
        whenever(accountClient.getAccount(1)).thenReturn(poorAccount)

        assertThrows(IllegalStateException::class.java) { portfolioService.executeTrade(trade) }
        verify(accountClient, never()).updateBalance(any(), any())
        verify(holdingRepository, never()).save(any())
    }

    @Test
    fun `executeTrade BUY fund not found throws IllegalArgumentException`() {
        val trade = TradeDto(accountId = 1, fundId = 99, quantity = BigDecimal("5"), price = BigDecimal("100.00"), type = "BUY")
        whenever(fundClient.getFund(99)).thenReturn(null)

        assertThrows(IllegalArgumentException::class.java) { portfolioService.executeTrade(trade) }
    }

    // --- SELL tests ---

    @Test
    fun `executeTrade SELL partial sell reduces quantity and credits proceeds`() {
        val holding = Holding(
            id = 1, accountId = 1, fundId = 1,
            quantity = BigDecimal("10"), averagePrice = BigDecimal("80.00"),
            currentValue = BigDecimal("1000.00")
        )
        val trade = TradeDto(accountId = 1, fundId = 1, quantity = BigDecimal("4"), price = BigDecimal("100.00"), type = "SELL")
        whenever(fundClient.getFund(1)).thenReturn(fund)
        whenever(holdingRepository.findByAccountIdAndFundId(1, 1)).thenReturn(holding)
        whenever(holdingRepository.save(any<Holding>())).thenAnswer { it.arguments[0] }

        val result = portfolioService.executeTrade(trade)

        assertEquals(BigDecimal("6"), result.quantity)
        val depositCaptor = argumentCaptor<DepositDto>()
        verify(accountClient).updateBalance(eq(1L), depositCaptor.capture())
        assertEquals(BigDecimal("400.00"), depositCaptor.firstValue.amount)
        verify(holdingRepository).save(any())
        verify(holdingRepository, never()).delete(any())
    }

    @Test
    fun `executeTrade SELL full sell deletes holding and credits proceeds`() {
        val holding = Holding(
            id = 1, accountId = 1, fundId = 1,
            quantity = BigDecimal("10"), averagePrice = BigDecimal("80.00"),
            currentValue = BigDecimal("1000.00")
        )
        val trade = TradeDto(accountId = 1, fundId = 1, quantity = BigDecimal("10"), price = BigDecimal("100.00"), type = "SELL")
        whenever(fundClient.getFund(1)).thenReturn(fund)
        whenever(holdingRepository.findByAccountIdAndFundId(1, 1)).thenReturn(holding)

        portfolioService.executeTrade(trade)

        val depositCaptor = argumentCaptor<DepositDto>()
        verify(accountClient).updateBalance(eq(1L), depositCaptor.capture())
        assertEquals(BigDecimal("1000.00"), depositCaptor.firstValue.amount)
        verify(holdingRepository).delete(holding)
        verify(holdingRepository, never()).save(any())
    }

    @Test
    fun `executeTrade SELL insufficient quantity throws IllegalStateException`() {
        val holding = Holding(
            id = 1, accountId = 1, fundId = 1,
            quantity = BigDecimal("3"), averagePrice = BigDecimal("80.00"),
            currentValue = BigDecimal("300.00")
        )
        val trade = TradeDto(accountId = 1, fundId = 1, quantity = BigDecimal("5"), price = BigDecimal("100.00"), type = "SELL")
        whenever(fundClient.getFund(1)).thenReturn(fund)
        whenever(holdingRepository.findByAccountIdAndFundId(1, 1)).thenReturn(holding)

        assertThrows(IllegalStateException::class.java) { portfolioService.executeTrade(trade) }
        verify(accountClient, never()).updateBalance(any(), any())
    }

    @Test
    fun `executeTrade SELL no holding throws IllegalArgumentException`() {
        val trade = TradeDto(accountId = 1, fundId = 1, quantity = BigDecimal("5"), price = BigDecimal("100.00"), type = "SELL")
        whenever(fundClient.getFund(1)).thenReturn(fund)
        whenever(holdingRepository.findByAccountIdAndFundId(1, 1)).thenReturn(null)

        assertThrows(IllegalArgumentException::class.java) { portfolioService.executeTrade(trade) }
    }

    @Test
    fun `executeTrade unsupported type throws IllegalArgumentException`() {
        val trade = TradeDto(accountId = 1, fundId = 1, quantity = BigDecimal("5"), price = BigDecimal("100.00"), type = "HOLD")

        assertThrows(IllegalArgumentException::class.java) { portfolioService.executeTrade(trade) }
    }

    // --- getPortfolio tests ---

    @Test
    fun `getPortfolio returns holdings with fund details and total value`() {
        val holding1 = Holding(id = 1, accountId = 1, fundId = 1, quantity = BigDecimal("5"), averagePrice = BigDecimal("90.00"), currentValue = BigDecimal("500.00"))
        val holding2 = Holding(id = 2, accountId = 1, fundId = 2, quantity = BigDecimal("3"), averagePrice = BigDecimal("200.00"), currentValue = BigDecimal("600.00"))
        whenever(holdingRepository.findByAccountId(1)).thenReturn(listOf(holding1, holding2))
        whenever(fundClient.getFund(1)).thenReturn(fund)
        whenever(fundClient.getFund(2)).thenReturn(FundDto(id = 2, name = "Tech Fund", isin = "GB0002", currentPrice = BigDecimal("200.00")))

        val result = portfolioService.getPortfolio(1)

        assertEquals(1L, result.accountId)
        assertEquals(2, result.holdings.size)
        assertEquals("Global Equities Fund", result.holdings[0].fundName)
        assertEquals("Tech Fund", result.holdings[1].fundName)
        assertEquals(BigDecimal("1100.00"), result.totalValue)
    }

    // --- getPortfolioHistory tests ---

    @Test
    fun `getPortfolioHistory returns snapshots in chronological order`() {
        val snap3 = com.mbd.portfolio.entity.PortfolioValueSnapshot(id = 3, accountId = 1, totalValue = BigDecimal("300.00"), timestamp = java.time.LocalDateTime.of(2026, 1, 3, 10, 0))
        val snap2 = com.mbd.portfolio.entity.PortfolioValueSnapshot(id = 2, accountId = 1, totalValue = BigDecimal("200.00"), timestamp = java.time.LocalDateTime.of(2026, 1, 2, 10, 0))
        val snap1 = com.mbd.portfolio.entity.PortfolioValueSnapshot(id = 1, accountId = 1, totalValue = BigDecimal("100.00"), timestamp = java.time.LocalDateTime.of(2026, 1, 1, 10, 0))
        whenever(snapshotRepository.findByAccountIdOrderByTimestampDesc(eq(1L), any())).thenReturn(listOf(snap3, snap2, snap1))

        val result = portfolioService.getPortfolioHistory(1)

        assertEquals(3, result.size)
        assertEquals(BigDecimal("100.00"), result[0].totalValue)
        assertEquals(BigDecimal("200.00"), result[1].totalValue)
        assertEquals(BigDecimal("300.00"), result[2].totalValue)
    }
}
