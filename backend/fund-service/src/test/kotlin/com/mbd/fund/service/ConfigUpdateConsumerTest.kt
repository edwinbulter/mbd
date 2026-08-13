package com.mbd.fund.service

import com.mbd.fund.entity.Fund
import com.mbd.fund.repository.FundRepository
import com.mbd.shared.dto.FundConfigDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal

@ExtendWith(MockitoExtension::class)
class ConfigUpdateConsumerTest {

    @Mock private lateinit var fundRepository: FundRepository

    @InjectMocks private lateinit var consumer: ConfigUpdateConsumer

    @Test
    fun `handleConfigUpdate applies volatility and frequency to all funds`() {
        val fund1 = Fund(id = 1, name = "Fund A", isin = "GB001", currentPrice = BigDecimal("100.00"), volatility = 0.02, updateFrequencyMinutes = 5)
        val fund2 = Fund(id = 2, name = "Fund B", isin = "GB002", currentPrice = BigDecimal("200.00"), volatility = 0.05, updateFrequencyMinutes = 10)
        whenever(fundRepository.findAll()).thenReturn(listOf(fund1, fund2))
        whenever(fundRepository.save(any<Fund>())).thenAnswer { it.arguments[0] }

        consumer.handleConfigUpdate(FundConfigDto(volatility = 0.03, updateFrequencyMinutes = 2))

        assertEquals(0.03, fund1.volatility)
        assertEquals(2, fund1.updateFrequencyMinutes)
        assertEquals(0.03, fund2.volatility)
        assertEquals(2, fund2.updateFrequencyMinutes)
        verify(fundRepository, times(2)).save(any())
    }
}
