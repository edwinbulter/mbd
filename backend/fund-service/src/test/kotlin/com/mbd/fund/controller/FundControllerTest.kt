package com.mbd.fund.controller

import com.mbd.fund.entity.Fund
import com.mbd.fund.repository.FundRepository
import com.mbd.shared.dto.FundConfigDto
import com.mbd.shared.dto.FundDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.util.Optional

@ExtendWith(MockitoExtension::class)
class FundControllerTest {

    @Mock private lateinit var fundRepository: FundRepository

    @InjectMocks private lateinit var controller: FundController

    private val fund = Fund(
        id = 1, name = "Global Equities Fund", isin = "GB0001",
        currentPrice = BigDecimal("100.00"), currency = "EUR",
        volatility = 0.02, updateFrequencyMinutes = 5
    )

    @Test
    fun `createFund saves and returns dto`() {
        val dto = FundDto(name = "Global Equities Fund", isin = "GB0001", currentPrice = BigDecimal("100.00"))
        whenever(fundRepository.save(any<Fund>())).thenAnswer { it.arguments[0] }

        val result = controller.createFund(dto)

        assertEquals(200, result.statusCode.value())
        assertEquals("Global Equities Fund", result.body!!.name)
        verify(fundRepository).save(any())
    }

    @Test
    fun `listFunds returns all funds`() {
        whenever(fundRepository.findAll()).thenReturn(listOf(fund))

        val result = controller.listFunds()

        assertEquals(200, result.statusCode.value())
        assertEquals(1, result.body!!.size)
        assertEquals("Global Equities Fund", result.body!![0].name)
    }

    @Test
    fun `getFund found returns dto`() {
        whenever(fundRepository.findById(1)).thenReturn(Optional.of(fund))

        val result = controller.getFund(1)

        assertEquals(200, result.statusCode.value())
        assertEquals("Global Equities Fund", result.body!!.name)
    }

    @Test
    fun `getFund notFound returns 404`() {
        whenever(fundRepository.findById(99)).thenReturn(Optional.empty())

        val result = controller.getFund(99)

        assertEquals(404, result.statusCode.value())
    }

    @Test
    fun `updateFund found updates and returns dto`() {
        val dto = FundDto(name = "Updated Fund", isin = "GB0001", currentPrice = BigDecimal("110.00"), currency = "USD", volatility = 0.05, updateFrequencyMinutes = 10)
        whenever(fundRepository.findById(1)).thenReturn(Optional.of(fund))
        whenever(fundRepository.save(any<Fund>())).thenAnswer { it.arguments[0] }

        val result = controller.updateFund(1, dto)

        assertEquals(200, result.statusCode.value())
        assertEquals("Updated Fund", result.body!!.name)
        assertEquals("USD", result.body!!.currency)
        assertEquals(0.05, result.body!!.volatility)
    }

    @Test
    fun `updateFund notFound returns 404`() {
        whenever(fundRepository.findById(99)).thenReturn(Optional.empty())

        val result = controller.updateFund(99, FundDto(name = "X", isin = "X", currentPrice = BigDecimal.ONE))

        assertEquals(404, result.statusCode.value())
    }

    @Test
    fun `deleteFund found returns 204`() {
        whenever(fundRepository.existsById(1)).thenReturn(true)

        val result = controller.deleteFund(1)

        assertEquals(204, result.statusCode.value())
        verify(fundRepository).deleteById(1)
    }

    @Test
    fun `deleteFund notFound returns 404`() {
        whenever(fundRepository.existsById(99)).thenReturn(false)

        val result = controller.deleteFund(99)

        assertEquals(404, result.statusCode.value())
        verify(fundRepository, never()).deleteById(any())
    }

    @Test
    fun `updateConfig found updates volatility and frequency`() {
        val config = FundConfigDto(volatility = 0.03, updateFrequencyMinutes = 2)
        whenever(fundRepository.findById(1)).thenReturn(Optional.of(fund))
        whenever(fundRepository.save(any<Fund>())).thenAnswer { it.arguments[0] }

        val result = controller.updateConfig(1, config)

        assertEquals(200, result.statusCode.value())
        assertEquals(0.03, result.body!!.volatility)
        assertEquals(2, result.body!!.updateFrequencyMinutes)
    }

    @Test
    fun `updateConfig notFound returns 404`() {
        whenever(fundRepository.findById(99)).thenReturn(Optional.empty())

        val result = controller.updateConfig(99, FundConfigDto(volatility = 0.03, updateFrequencyMinutes = 2))

        assertEquals(404, result.statusCode.value())
    }
}
