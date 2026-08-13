package com.mbd.fund.service

import com.mbd.fund.entity.Fund
import com.mbd.fund.repository.FundRepository
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
class PriceUpdateSchedulerTest {

    @Mock private lateinit var fundRepository: FundRepository
    @Mock private lateinit var priceProducer: FundPriceProducer

    @InjectMocks private lateinit var scheduler: PriceUpdateScheduler

    @Test
    fun `updatePrices fund not due skips update`() {
        val now = LocalDateTime.now()
        val fund = Fund(
            id = 1, name = "Test Fund", isin = "GB0001",
            currentPrice = BigDecimal("100.00"),
            volatility = 0.03,
            updateFrequencyMinutes = 5,
            updatedAt = now
        )
        whenever(fundRepository.findAll()).thenReturn(listOf(fund))

        scheduler.updatePrices()

        verify(fundRepository, never()).save(any())
        verifyNoInteractions(priceProducer)
    }

    @Test
    fun `updatePrices fund due updates price and publishes`() {
        val now = LocalDateTime.now()
        val originalPrice = BigDecimal("100.00")
        val fund = Fund(
            id = 1, name = "Test Fund", isin = "GB0001",
            currentPrice = originalPrice,
            volatility = 0.03,
            updateFrequencyMinutes = 1,
            updatedAt = now.minusMinutes(2)
        )
        whenever(fundRepository.findAll()).thenReturn(listOf(fund))
        whenever(fundRepository.save(any<Fund>())).thenAnswer { it.arguments[0] }

        scheduler.updatePrices()

        verify(fundRepository).save(any())
        assertNotEquals(originalPrice, fund.currentPrice)
        assertTrue(fund.updatedAt.isAfter(now.minusMinutes(1)))
    }

    @Test
    fun `updatePrices price stays within volatility bounds`() {
        val now = LocalDateTime.now()
        val fund = Fund(
            id = 1, name = "Test Fund", isin = "GB0001",
            currentPrice = BigDecimal("100.00"),
            volatility = 0.03,
            updateFrequencyMinutes = 1,
            updatedAt = now.minusMinutes(2)
        )
        whenever(fundRepository.findAll()).thenReturn(listOf(fund))
        whenever(fundRepository.save(any<Fund>())).thenAnswer { it.arguments[0] }

        repeat(50) {
            fund.updatedAt = now.minusMinutes(2)
            val priceBefore = fund.currentPrice
            scheduler.updatePrices()
            val priceAfter = fund.currentPrice
            val maxChange = priceBefore.multiply(BigDecimal("0.03"))
            val lowerBound = priceBefore.subtract(maxChange)
            val upperBound = priceBefore.add(maxChange)
            assertTrue(priceAfter >= lowerBound, "Price $priceAfter should be >= $lowerBound (prev=$priceBefore)")
            assertTrue(priceAfter <= upperBound, "Price $priceAfter should be <= $upperBound (prev=$priceBefore)")
        }
    }
}
