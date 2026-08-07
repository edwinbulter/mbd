# Backend Services Implementation Guide

This guide provides detailed step-by-step instructions for implementing the MBD backend services using Spring Boot and Kotlin.

## Prerequisites

- Infrastructure setup completed (Phase 1)
- Kind cluster running with Istio, PostgreSQL, Kafka, and Keycloak
- PostgreSQL database accessible at `postgresql.mbd-infra.svc.cluster.local:5432`
- Kafka accessible at `kafka.mbd-infra.svc.cluster.local:9092`
- Keycloak accessible at `keycloak.mbd-infra.svc.cluster.local:8080`
- JDK 17+ installed
- Gradle installed
- Docker installed
- kubectl configured

## Project Structure (Monorepo)

```
mbd/
├── backend/
│   ├── user-service/
│   ├── account-service/
│   ├── fund-service/
│   ├── portfolio-service/
│   ├── admin-service/
│   └── shared/ (common code)
├── frontend/
│   ├── customer-frontend/
│   └── admin-frontend/
├── infrastructure/
│   ├── k8s/
│   └── argocd/
└── doc/
```

## 1. Shared Components

### 1.1 Create Shared Module

```bash
# Create shared module directory
mkdir -p backend/shared/src/main/kotlin/com/mbd/shared

# Create build.gradle.kts for shared module
# Include common dependencies: Spring Boot Starter, Kotlin, Jackson, etc.
```

### 1.2 Shared Components Implementation

- **Common DTOs and models**: User, Account, Fund, Portfolio, Transaction DTOs
- **Database configuration**: Spring Boot datasource configuration for PostgreSQL
- **Kafka configuration**: Producer/Consumer configuration templates
- **Security utilities**: JWT parsing, role validation utilities
- **Istio mTLS configuration**: Service discovery and communication utilities

## 2. User Service

### 2.1 Create User Service Project

```bash
# Create Spring Boot project with Kotlin
# Dependencies: Spring Web, Spring Data JPA, PostgreSQL, Keycloak Spring Boot Starter
mkdir -p backend/user-service/src/main/kotlin/com/mbd/user
```

### 2.2 Configure Application Properties

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://postgresql.mbd-infra.svc.cluster.local:5432/mbd
    username: mbd
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: update
keycloak:
  auth-server-url: http://keycloak.mbd-infra.svc.cluster.local:8080
  realm: mbd
  resource: mbd-backend
  bearer-only: true
```

### 2.3 Create User Entity

```kotlin
@Entity
@Table(name = "users")
data class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val keycloakId: String,
    val email: String,
    val firstName: String,
    val lastName: String,
    val role: String
)
```

### 2.4 Create Repository

```kotlin
interface UserRepository : JpaRepository<User, Long> {
    fun findByKeycloakId(keycloakId: String): User?
}
```

### 2.5 Create REST Endpoints

```kotlin
@RestController
@RequestMapping("/api/users")
class UserController(
    private val userRepository: UserRepository
) {
    @GetMapping("/profile")
    fun getProfile(@RequestHeader("Authorization") authHeader: String): ResponseEntity<UserDto>
    
    @PostMapping("/register")
    fun register(@RequestBody registrationDto: RegistrationDto): ResponseEntity<UserDto>
}
```

### 2.6 Create Kubernetes Manifests

```yaml
# infrastructure/k8s/user-service/deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-service
  namespace: mbd
spec:
  replicas: 1
  selector:
    matchLabels:
      app: user-service
  template:
    metadata:
      labels:
        app: user-service
    spec:
      containers:
        - name: user-service
          image: user-service:latest
          ports:
            - containerPort: 8080
          env:
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-secret
                  key: password
```

### 2.7 Create VirtualService

```yaml
# infrastructure/k8s/istio/user-service-vs.yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: user-service
  namespace: mbd
spec:
  hosts:
    - "*"
  gateways:
    - mbd/mbd-gateway
  http:
    - match:
        - uri:
            prefix: /api/users
      route:
        - destination:
            host: user-service
            port:
              number: 8080
```

## 3. Account Service

### 3.1 Create Account Service Project

```bash
# Create Spring Boot project with Kotlin
# Dependencies: Spring Web, Spring Data JPA, PostgreSQL, Keycloak
mkdir -p backend/account-service/src/main/kotlin/com/mbd/account
```

### 3.2 Create Account Entity

```kotlin
@Entity
@Table(name = "accounts")
data class Account(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val userId: Long,
    val accountNumber: String,
    val balance: BigDecimal,
    val createdAt: LocalDateTime
)
```

### 3.3 Create Transaction Entity

```kotlin
@Entity
@Table(name = "transactions")
data class Transaction(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val accountId: Long,
    val amount: BigDecimal,
    val type: TransactionType,
    val createdAt: LocalDateTime
)
```

### 3.4 Create REST Endpoints

```kotlin
@RestController
@RequestMapping("/api/accounts")
class AccountController(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val userClient: UserClient
) {
    @PostMapping
    fun createAccount(@RequestBody request: CreateAccountDto): ResponseEntity<AccountDto>
    
    @PostMapping("/{accountId}/deposit")
    fun deposit(@PathVariable accountId: Long, @RequestBody request: DepositDto): ResponseEntity<AccountDto>
    
    @GetMapping("/{accountId}")
    fun getAccount(@PathVariable accountId: Long): ResponseEntity<AccountDto>
}
```

### 3.5 Create User Client for Integration

```kotlin
@FeignClient(name = "user-service", url = "http://user-service.mbd.svc.cluster.local:8080")
interface UserClient {
    @GetMapping("/api/users/profile")
    fun getUserProfile(@RequestHeader("Authorization") authHeader: String): UserDto
}
```

### 3.6 Create Kubernetes Manifests and VirtualService

Similar to user-service, create deployment, service, and VirtualService for account-service.

## 4. Fund Service

### 4.1 Create Fund Service Project

```bash
# Create Spring Boot project with Kotlin
# Dependencies: Spring Web, Spring Data JPA, PostgreSQL, Kafka, Spring Scheduling
mkdir -p backend/fund-service/src/main/kotlin/com/mbd/fund
```

### 4.2 Create Fund Entity

```kotlin
@Entity
@Table(name = "funds")
data class Fund(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val name: String,
    val isin: String,
    val currentPrice: BigDecimal,
    val currency: String,
    val volatility: Double = 0.02,
    val updateFrequencyMinutes: Int = 5
)
```

### 4.3 Configure Kafka Producer

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: kafka.mbd-infra.svc.cluster.local:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

### 4.4 Create Kafka Producer Service

```kotlin
@Service
class FundPriceProducer(
    private val kafkaTemplate: KafkaTemplate<String, FundPriceUpdate>
) {
    fun publishPriceUpdate(update: FundPriceUpdate) {
        kafkaTemplate.send("fund-price-updates", update.fundId.toString(), update)
    }
}
```

### 4.5 Create Scheduled Job for Price Updates

```kotlin
@Service
class PriceUpdateScheduler(
    private val fundRepository: FundRepository,
    private val priceProducer: FundPriceProducer
) {
    @Scheduled(fixedRate = 300000) // 5 minutes default
    fun updatePrices() {
        val funds = fundRepository.findAll()
        funds.forEach { fund ->
            val newPrice = calculateRandomPrice(fund.currentPrice, fund.volatility)
            fund.currentPrice = newPrice
            fundRepository.save(fund)
            priceProducer.publishPriceUpdate(FundPriceUpdate(fund.id!!, newPrice))
        }
    }
}
```

### 4.6 Create Admin Configuration Endpoints

```kotlin
@RestController
@RequestMapping("/api/funds/admin")
class FundAdminController(
    private val fundRepository: FundRepository
) {
    @PutMapping("/{fundId}/config")
    fun updateConfig(@PathVariable fundId: Long, @RequestBody config: FundConfigDto): ResponseEntity<FundDto>
}
```

### 4.7 Create Kubernetes Manifests and VirtualService

Similar to user-service, create deployment, service, and VirtualService for fund-service.

## 5. Portfolio Service

### 5.1 Create Portfolio Service Project

```bash
# Create Spring Boot project with Kotlin
# Dependencies: Spring Web, Spring Data JPA, PostgreSQL, Kafka
mkdir -p backend/portfolio-service/src/main/kotlin/com/mbd/portfolio
```

### 5.2 Create Holding Entity

```kotlin
@Entity
@Table(name = "holdings")
data class Holding(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val accountId: Long,
    val fundId: Long,
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val currentValue: BigDecimal
)
```

### 5.3 Configure Kafka Consumer

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: kafka.mbd-infra.svc.cluster.local:9092
    consumer:
      group-id: portfolio-service
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
```

### 5.4 Create Kafka Consumer Service

```kotlin
@Service
class FundPriceConsumer(
    private val holdingRepository: HoldingRepository,
    private val portfolioService: PortfolioService
) {
    @KafkaListener(topics = ["fund-price-updates"])
    fun handlePriceUpdate(update: FundPriceUpdate) {
        val holdings = holdingRepository.findByFundId(update.fundId)
        holdings.forEach { holding ->
            holding.currentValue = holding.quantity * update.newPrice
            holdingRepository.save(holding)
        }
        portfolioService.publishPortfolioUpdates(holdings)
    }
}
```

### 5.5 Create Portfolio Calculation Service

```kotlin
@Service
class PortfolioService(
    private val holdingRepository: HoldingRepository
) {
    fun getPortfolio(accountId: Long): PortfolioDto {
        val holdings = holdingRepository.findByAccountId(accountId)
        val totalValue = holdings.sumOf { it.currentValue }
        return PortfolioDto(accountId, holdings, totalValue)
    }
}
```

### 5.6 Create REST Endpoints

```kotlin
@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(
    private val portfolioService: PortfolioService
) {
    @GetMapping("/{accountId}")
    fun getPortfolio(@PathVariable accountId: Long): ResponseEntity<PortfolioDto>
}
```

### 5.7 Create Kubernetes Manifests and VirtualService

Similar to user-service, create deployment, service, and VirtualService for portfolio-service.

## 6. Admin Service

### 6.1 Create Admin Service Project

```bash
# Create Spring Boot project with Kotlin
# Dependencies: Spring Web, Spring Data JPA, PostgreSQL, Keycloak
mkdir -p backend/admin-service/src/main/kotlin/com/mbd/admin
```

### 6.2 Create SystemConfig Entity

```kotlin
@Entity
@Table(name = "system_config")
data class SystemConfig(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val key: String,
    val value: String,
    val updatedAt: LocalDateTime
)
```

### 6.3 Create Configuration Endpoints

```kotlin
@RestController
@RequestMapping("/api/admin/config")
class AdminConfigController(
    private val configRepository: SystemConfigRepository
) {
    @GetMapping("/price-update")
    fun getPriceUpdateConfig(): ResponseEntity<PriceUpdateConfigDto>
    
    @PutMapping("/price-update")
    fun updatePriceUpdateConfig(@RequestBody config: PriceUpdateConfigDto): ResponseEntity<PriceUpdateConfigDto>
}
```

### 6.4 Add Role-Based Access Control

```kotlin
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { authz ->
                authz
                    .requestMatchers("/api/admin/**").hasRole("employee")
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.jwtAuthenticationConverter(KeycloakJwtConverter())
                }
            }
        return http.build()
    }
}
```

### 6.5 Create Monitoring Endpoints

```kotlin
@RestController
@RequestMapping("/api/admin/monitoring")
class MonitoringController {
    @GetMapping("/system-health")
    fun getSystemHealth(): ResponseEntity<SystemHealthDto>
    
    @GetMapping("/active-users")
    fun getActiveUsers(): ResponseEntity<List<UserDto>>
}
```

### 6.6 Create Kubernetes Manifests and VirtualService

Similar to user-service, create deployment, service, and VirtualService for admin-service.

## 7. Database Schema Initialization

Use Flyway for SQL migration scripts. Create migration files in `src/main/resources/db/migration/`.

### 7.1 Configure Flyway

Add Flyway dependency to `build.gradle.kts`:

```kotlin
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")
```

Configure Flyway in `application.yml`:

```yaml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
  jpa:
    hibernate:
      ddl-auto: validate  # Changed from update to validate
```

### 7.2 Create Migration Scripts

Create the following migration files in `src/main/resources/db/migration/`:

#### V1__Create_Users_Table.sql

```sql
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    keycloak_id VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    role VARCHAR(50) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_keycloak_id ON users(keycloak_id);
CREATE INDEX idx_users_email ON users(email);
```

#### V2__Create_Accounts_Table.sql

```sql
CREATE TABLE accounts (
    id SERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    account_number VARCHAR(20) UNIQUE NOT NULL,
    balance DECIMAL(19, 2) DEFAULT 0.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_accounts_user_id ON accounts(user_id);
CREATE INDEX idx_accounts_account_number ON accounts(account_number);
```

#### V3__Create_Transactions_Table.sql

```sql
CREATE TABLE transactions (
    id SERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    amount DECIMAL(19, 2) NOT NULL,
    type VARCHAR(20) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE
);

CREATE INDEX idx_transactions_account_id ON transactions(account_id);
CREATE INDEX idx_transactions_created_at ON transactions(created_at);
```

#### V4__Create_Funds_Table.sql

```sql
CREATE TABLE funds (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    isin VARCHAR(12) UNIQUE NOT NULL,
    current_price DECIMAL(19, 2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'EUR',
    volatility DECIMAL(5, 4) DEFAULT 0.02,
    update_frequency_minutes INT DEFAULT 5,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_funds_isin ON funds(isin);
CREATE INDEX idx_funds_name ON funds(name);
```

#### V5__Create_Holdings_Table.sql

```sql
CREATE TABLE holdings (
    id SERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    fund_id BIGINT NOT NULL,
    quantity DECIMAL(19, 4) NOT NULL,
    average_price DECIMAL(19, 2) NOT NULL,
    current_value DECIMAL(19, 2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    FOREIGN KEY (fund_id) REFERENCES funds(id) ON DELETE CASCADE,
    UNIQUE(account_id, fund_id)
);

CREATE INDEX idx_holdings_account_id ON holdings(account_id);
CREATE INDEX idx_holdings_fund_id ON holdings(fund_id);
```

#### V6__Create_System_Config_Table.sql

```sql
CREATE TABLE system_config (
    id SERIAL PRIMARY KEY,
    key VARCHAR(100) UNIQUE NOT NULL,
    value TEXT NOT NULL,
    description VARCHAR(255),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_system_config_key ON system_config(key);
```

#### V7__Insert_Default_Config_Values.sql

```sql
INSERT INTO system_config (key, value, description) VALUES
('price_update_frequency_minutes', '5', 'Default frequency for fund price updates in minutes'),
('price_update_volatility', '0.02', 'Default volatility for random price generation'),
('system_maintenance_mode', 'false', 'System maintenance mode flag');
```

### 7.3 Running Migrations

Flyway migrations will run automatically on application startup. To run migrations manually:

```bash
# Using Gradle
./gradlew flywayMigrate

# Or let Spring Boot handle it on startup
./gradlew bootRun
```

### 7.4 Migration Best Practices

- Always create new migration files with incrementing version numbers (V1, V2, V3, etc.)
- Never modify existing migration files after they have been applied
- Use descriptive names for migration files (e.g., V2__Create_Accounts_Table.sql)
- Test migrations on a local database before deploying
- Use `baseline-on-migrate: true` for existing databases

## 8. Build and Deployment

### 8.1 Create Docker Images

```bash
# Build each service
cd backend/user-service && ./gradlew bootJar
docker build -t user-service:latest .
cd ../account-service && ./gradlew bootJar
docker build -t account-service:latest .
# Repeat for other services
```

### 8.2 Load Images into Kind Cluster

```bash
kind load docker-image user-service:latest --name mbd-cluster
kind load docker-image account-service:latest --name mbd-cluster
# Repeat for other services
```

### 8.3 Apply Kubernetes Manifests

```bash
kubectl apply -f infrastructure/k8s/user-service/deployment.yaml
kubectl apply -f infrastructure/k8s/user-service/service.yaml
kubectl apply -f infrastructure/k8s/istio/user-service-vs.yaml
# Repeat for other services
```

### 8.4 Create ArgoCD Applications

```yaml
# infrastructure/argocd/user-service-app.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: mbd-user-service
  namespace: argocd
spec:
  project: default
  source:
    repoURL: git@github.com:edwinbulter/mbd.git
    targetRevision: main
    path: infrastructure/k8s/user-service
  destination:
    server: https://kubernetes.default.svc
    namespace: mbd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

Create similar ArgoCD applications for account-service, fund-service, portfolio-service, and admin-service.

## 9. Testing

### 9.1 Test Service Communication

```bash
# Test user service
kubectl port-forward -n mbd svc/user-service 8080:8080
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/users/profile

# Test account service
kubectl port-forward -n mbd svc/account-service 8081:8080
curl -H "Authorization: Bearer <token>" http://localhost:8081/api/accounts
```

### 9.2 Test Kafka Integration

```bash
# Check Kafka topics
kubectl exec -n mbd-infra kafka-0 -- /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092

# Verify price updates are being published
kubectl logs -n mbd -l app=fund-service
```

### 9.3 Test Istio Routing

```bash
# Test through Istio Gateway
kubectl port-forward -n istio-system svc/istio-ingressgateway 8080:80
curl -H "Authorization: Bearer <token>" http://localhost:8080/api/users/profile
```

## 10. Troubleshooting

### Service Not Starting

```bash
# Check pod logs
kubectl logs -n mbd -l app=user-service

# Check pod status
kubectl describe pod -n mbd -l app=user-service
```

### Database Connection Issues

```bash
# Test database connectivity
kubectl exec -n mbd user-service-xxx -- nc -zv postgresql.mbd-infra.svc.cluster.local 5432
```

### Kafka Connection Issues

```bash
# Test Kafka connectivity
kubectl exec -n mbd fund-service-xxx -- nc -zv kafka.mbd-infra.svc.cluster.local 9092
```

### Istio Routing Issues

```bash
# Check VirtualService configuration
kubectl get virtualservice -n mbd

# Check Gateway configuration
kubectl get gateway -n mbd
```

## Next Steps

After completing backend services implementation, proceed to:
- Frontend implementation (Phase 3)
- End-to-end testing
- Performance optimization
