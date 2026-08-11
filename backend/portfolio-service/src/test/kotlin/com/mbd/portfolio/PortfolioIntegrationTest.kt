package com.mbd.portfolio

import com.mbd.portfolio.entity.Holding
import com.mbd.portfolio.repository.HoldingRepository
import com.mbd.portfolio.repository.PortfolioValueSnapshotRepository
import com.mbd.shared.dto.FundPriceUpdate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import com.mbd.portfolio.client.AccountClient
import com.mbd.portfolio.client.FundClient

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PortfolioIntegrationTest {

    @Autowired
    private lateinit var holdingRepository: HoldingRepository

    @Autowired
    private lateinit var snapshotRepository: PortfolioValueSnapshotRepository

    @Autowired
    private lateinit var kafkaTemplate: KafkaTemplate<String, Any>

    @MockBean
    private lateinit var accountClient: AccountClient

    @MockBean
    private lateinit var fundClient: FundClient

    companion object {
        init {
            System.setProperty("docker.api.version", "1.40")
        }

        @Container
        val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))
            .withDatabaseName("mbd")
            .withUsername("mbdadmin")
            .withPassword("mbdpassword")

        @Container
        val kafka = KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))

        @JvmStatic
        @DynamicPropertySource
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers)
        }
    }

    @Test
    fun `should update holding value and create snapshot when fund price update is received`() {
        // Given: A holding exists in the database
        val accountId = 1L
        val fundId = 100L
        val initialQuantity = BigDecimal("10.0")
        val initialPrice = BigDecimal("100.0")
        
        val holding = Holding(
            accountId = accountId,
            fundId = fundId,
            quantity = initialQuantity,
            averagePrice = initialPrice,
            currentValue = initialQuantity.multiply(initialPrice),
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
        holdingRepository.save(holding)

        // When: A FundPriceUpdate is sent to Kafka
        val newPrice = BigDecimal("110.0")
        val update = FundPriceUpdate(fundId = fundId, newPrice = newPrice)
        kafkaTemplate.send("fund-price-updates", fundId.toString(), update)

        // Then: The holding is updated and a snapshot is created
        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            val updatedHolding = holdingRepository.findByAccountIdAndFundId(accountId, fundId)
            assertThat(updatedHolding).isNotNull
            assertThat(updatedHolding?.currentValue?.setScale(2)).isEqualTo(initialQuantity.multiply(newPrice).setScale(2))

            val snapshots = snapshotRepository.findAll().filter { it.accountId == accountId }
            assertThat(snapshots).isNotEmpty
            assertThat(snapshots.first().totalValue.setScale(2)).isEqualTo(initialQuantity.multiply(newPrice).setScale(2))
        }
    }
}
