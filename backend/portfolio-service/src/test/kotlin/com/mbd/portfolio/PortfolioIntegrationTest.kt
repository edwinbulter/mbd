package com.mbd.portfolio

import com.mbd.portfolio.entity.Holding
import com.mbd.portfolio.repository.HoldingRepository
import com.mbd.portfolio.repository.PortfolioValueSnapshotRepository
import com.mbd.shared.dto.FundPriceUpdate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.JdbcTemplate
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

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @MockBean
    private lateinit var accountClient: AccountClient

    @MockBean
    private lateinit var fundClient: FundClient

    @BeforeEach
    fun insertReferenceData() {
        // The holdings table has FK constraints to accounts(id) and funds(id),
        // and accounts has a FK to users(id). In the shared cluster database
        // these rows are created by user-service, account-service and
        // fund-service. In the isolated Testcontainers database we insert
        // minimal reference rows so the FK constraints are satisfied.
        jdbcTemplate.update(
            "INSERT INTO users (id, keycloak_id, email, first_name, last_name, role) " +
                "VALUES (1, 'test-kc-id', 'test@example.com', 'Test', 'User', 'USER') " +
                "ON CONFLICT (id) DO NOTHING"
        )
        jdbcTemplate.update(
            "INSERT INTO accounts (id, user_id, account_number, balance) " +
                "VALUES (1, 1, 'NL01TEST0000000001', 0.00) " +
                "ON CONFLICT (id) DO NOTHING"
        )
        jdbcTemplate.update(
            "INSERT INTO funds (id, name, isin, current_price) " +
                "VALUES (100, 'Test Fund', 'NL0000001007', 100.00) " +
                "ON CONFLICT (id) DO NOTHING"
        )
    }

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
            registry.add("spring.kafka.consumer.auto-offset-reset") { "earliest" }
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
        kafkaTemplate.send("fund-price-updates", fundId.toString(), update).get(10, TimeUnit.SECONDS)

        // Then: The holding is updated and a snapshot is created
        await.atMost(Duration.ofSeconds(30)).untilAsserted {
            val updatedHolding = holdingRepository.findByAccountIdAndFundId(accountId, fundId)
            assertThat(updatedHolding).isNotNull
            assertThat(updatedHolding?.currentValue?.setScale(2)).isEqualTo(initialQuantity.multiply(newPrice).setScale(2))

            val snapshots = snapshotRepository.findAll().filter { it.accountId == accountId }
            assertThat(snapshots).isNotEmpty
            assertThat(snapshots.first().totalValue.setScale(2)).isEqualTo(initialQuantity.multiply(newPrice).setScale(2))
        }
    }
}
