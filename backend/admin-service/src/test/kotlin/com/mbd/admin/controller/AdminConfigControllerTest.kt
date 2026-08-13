package com.mbd.admin.controller

import com.mbd.admin.entity.SystemConfig
import com.mbd.admin.repository.SystemConfigRepository
import com.mbd.shared.dto.FundConfigDto
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.kafka.core.KafkaTemplate

@ExtendWith(MockitoExtension::class)
class AdminConfigControllerTest {

    @Mock private lateinit var configRepository: SystemConfigRepository
    @Mock private lateinit var kafkaTemplate: KafkaTemplate<String, FundConfigDto>

    @InjectMocks private lateinit var controller: AdminConfigController

    private val volatilityConfig = SystemConfig(id = 1, key = "price_update_volatility", value = "0.02")
    private val frequencyConfig = SystemConfig(id = 2, key = "price_update_frequency_minutes", value = "5")

    @Test
    fun `getPriceUpdateConfig existing keys returns config`() {
        whenever(configRepository.findByKey("price_update_frequency_minutes")).thenReturn(frequencyConfig)
        whenever(configRepository.findByKey("price_update_volatility")).thenReturn(volatilityConfig)

        val result = controller.getPriceUpdateConfig()

        assertEquals(200, result.statusCode.value())
        assertEquals(0.02, result.body!!.volatility)
        assertEquals(5, result.body!!.updateFrequencyMinutes)
    }

    @Test
    fun `getPriceUpdateConfig no keys returns defaults`() {
        whenever(configRepository.findByKey("price_update_frequency_minutes")).thenReturn(null)
        whenever(configRepository.findByKey("price_update_volatility")).thenReturn(null)

        val result = controller.getPriceUpdateConfig()

        assertEquals(200, result.statusCode.value())
        assertEquals(0.02, result.body!!.volatility)
        assertEquals(5, result.body!!.updateFrequencyMinutes)
    }

    @Test
    fun `updatePriceUpdateConfig existing keys saves and publishes to kafka`() {
        val config = FundConfigDto(volatility = 0.03, updateFrequencyMinutes = 2)
        whenever(configRepository.findByKey("price_update_frequency_minutes")).thenReturn(frequencyConfig)
        whenever(configRepository.findByKey("price_update_volatility")).thenReturn(volatilityConfig)

        val result = controller.updatePriceUpdateConfig(config)

        assertEquals(200, result.statusCode.value())
        assertEquals(0.03, result.body!!.volatility)
        assertEquals(2, result.body!!.updateFrequencyMinutes)

        verify(configRepository).save(frequencyConfig)
        verify(configRepository).save(volatilityConfig)
        assertEquals("2", frequencyConfig.value)
        assertEquals("0.03", volatilityConfig.value)
        verify(kafkaTemplate).send(eq("config-updates"), eq("config"), eq(config))
    }

    @Test
    fun `updatePriceUpdateConfig new keys creates configs and publishes`() {
        val config = FundConfigDto(volatility = 0.05, updateFrequencyMinutes = 10)
        whenever(configRepository.findByKey("price_update_frequency_minutes")).thenReturn(null)
        whenever(configRepository.findByKey("price_update_volatility")).thenReturn(null)

        val result = controller.updatePriceUpdateConfig(config)

        assertEquals(200, result.statusCode.value())
        assertEquals(0.05, result.body!!.volatility)
        assertEquals(10, result.body!!.updateFrequencyMinutes)

        val captor = argumentCaptor<SystemConfig>()
        verify(configRepository, times(2)).save(captor.capture())
        val saved = captor.allValues
        assertEquals("price_update_frequency_minutes", saved[0].key)
        assertEquals("10", saved[0].value)
        assertEquals("price_update_volatility", saved[1].key)
        assertEquals("0.05", saved[1].value)
        verify(kafkaTemplate).send(eq("config-updates"), eq("config"), eq(config))
    }

    @Test
    fun `getAllConfigs returns all`() {
        whenever(configRepository.findAll()).thenReturn(listOf(volatilityConfig, frequencyConfig))

        val result = controller.getAllConfigs()

        assertEquals(200, result.statusCode.value())
        assertEquals(2, result.body!!.size)
    }
}
