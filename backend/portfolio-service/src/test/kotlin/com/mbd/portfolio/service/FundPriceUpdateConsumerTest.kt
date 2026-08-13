package com.mbd.portfolio.service

import com.mbd.portfolio.entity.Holding
import com.mbd.portfolio.entity.PortfolioValueSnapshot
import com.mbd.portfolio.repository.HoldingRepository
import com.mbd.portfolio.repository.PortfolioValueSnapshotRepository
import com.mbd.shared.dto.FundPriceUpdate
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.time.LocalDateTime

@ExtendWith(MockitoExtension::class)
class FundPriceUpdateConsumerTest {

    @Mock private lateinit var holdingRepository: HoldingRepository
    @Mock private lateinit var snapshotRepository: PortfolioValueSnapshotRepository

    @InjectMocks private lateinit var consumer: FundPriceUpdateConsumer

    @Test
    fun `handlePriceUpdate updates holdings and creates snapshots per account`() {
        val timestamp = LocalDateTime.of(2026, 1, 1, 10, 0)
        val update = FundPriceUpdate(fundId = 1, newPrice = BigDecimal("110.00"), timestamp = timestamp)

        val holding1 = Holding(id = 1, accountId = 1, fundId = 1, quantity = BigDecimal("5"), averagePrice = BigDecimal("100.00"), currentValue = BigDecimal("500.00"))
        val holding2 = Holding(id = 2, accountId = 2, fundId = 1, quantity = BigDecimal("3"), averagePrice = BigDecimal("100.00"), currentValue = BigDecimal("300.00"))

        whenever(holdingRepository.findByFundId(1)).thenReturn(listOf(holding1, holding2))
        whenever(holdingRepository.findByAccountId(1)).thenReturn(listOf(holding1))
        whenever(holdingRepository.findByAccountId(2)).thenReturn(listOf(holding2))
        whenever(holdingRepository.save(any<Holding>())).thenAnswer { it.arguments[0] }

        consumer.handlePriceUpdate(update)

        assertEquals(BigDecimal("550.00"), holding1.currentValue)
        assertEquals(BigDecimal("330.00"), holding2.currentValue)

        val snapshotCaptor = argumentCaptor<PortfolioValueSnapshot>()
        verify(snapshotRepository, times(2)).save(snapshotCaptor.capture())
        val snapshots = snapshotCaptor.allValues
        assertEquals(1L, snapshots[0].accountId)
        assertEquals(BigDecimal("550.00"), snapshots[0].totalValue)
        assertEquals(2L, snapshots[1].accountId)
        assertEquals(BigDecimal("330.00"), snapshots[1].totalValue)
    }

    @Test
    fun `handlePriceUpdate with no holdings does nothing`() {
        val update = FundPriceUpdate(fundId = 99, newPrice = BigDecimal("110.00"))
        whenever(holdingRepository.findByFundId(99)).thenReturn(emptyList())

        consumer.handlePriceUpdate(update)

        verify(holdingRepository, never()).save(any())
        verify(snapshotRepository, never()).save(any())
    }

    @Test
    fun `handlePriceUpdate multiple holdings same account creates single snapshot`() {
        val timestamp = LocalDateTime.of(2026, 1, 1, 10, 0)
        val update = FundPriceUpdate(fundId = 1, newPrice = BigDecimal("110.00"), timestamp = timestamp)

        val holding1 = Holding(id = 1, accountId = 1, fundId = 1, quantity = BigDecimal("5"), averagePrice = BigDecimal("100.00"), currentValue = BigDecimal("500.00"))
        val holding2 = Holding(id = 2, accountId = 1, fundId = 1, quantity = BigDecimal("3"), averagePrice = BigDecimal("100.00"), currentValue = BigDecimal("300.00"))

        whenever(holdingRepository.findByFundId(1)).thenReturn(listOf(holding1, holding2))
        whenever(holdingRepository.findByAccountId(1)).thenReturn(listOf(holding1, holding2))
        whenever(holdingRepository.save(any<Holding>())).thenAnswer { it.arguments[0] }

        consumer.handlePriceUpdate(update)

        val snapshotCaptor = argumentCaptor<PortfolioValueSnapshot>()
        verify(snapshotRepository, times(1)).save(snapshotCaptor.capture())
        assertEquals(1L, snapshotCaptor.firstValue.accountId)
        assertEquals(BigDecimal("880.00"), snapshotCaptor.firstValue.totalValue)
    }
}
