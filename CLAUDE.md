# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MBD (My Bank Demo) is a demo investment-banking microservices application built to demonstrate:
- **Istio service mesh** with mTLS for service-to-service communication
- **ArgoCD GitOps** for Kubernetes deployment management
- **cert-manager PKI** for TLS certificate management
- **Keycloak SSO** for authentication with JWT validation

The stack: Spring Boot 3.1 + Kotlin 1.9 (backend), React 18 + Vite + TypeScript (frontend), PostgreSQL 15, Kafka 3.7 (KRaft), running on **Kind (Kubernetes in Docker)** cluster.

## Monorepo Structure

```
mbd/
├── backend/         # 5 Spring Boot microservices + shared module
├── frontend/        # 2 React SPAs (customer, admin)
├── infrastructure/  # K8s manifests, Istio, ArgoCD apps
└── doc/            # Architecture & implementation guides
```

## Common Development Commands

### Backend (Kotlin + Spring Boot)

All backend commands run from `backend/` directory:

```bash
cd backend

# Build all services
./gradlew build

# Build a specific service
./gradlew :user-service:bootJar
./gradlew :portfolio-service:bootJar

# Run all tests
./gradlew test

# Run tests for a specific service
./gradlew :account-service:test
./gradlew :fund-service:test

# Run specific unit tests (as commonly used in CI)
./gradlew test --tests "com.mbd.portfolio.service.*" --tests "com.mbd.fund.*" --tests "com.mbd.account.controller.*" --tests "com.mbd.user.controller.UserControllerTest" --tests "com.mbd.admin.controller.*" --no-daemon

# Run integration test (uses Testcontainers for PostgreSQL + Kafka)
./gradlew :portfolio-service:test --tests "com.mbd.portfolio.PortfolioIntegrationTest"

# Run database migration test
./gradlew :user-service:test --tests "com.mbd.user.DatabaseMigrationTest"

# Build Docker image for a service (run from service directory)
cd user-service
docker build -t user-service:latest .
cd ..

# CRITICAL: Load image into Kind cluster (required for Kind, not needed for OrbStack)
kind load docker-image user-service:latest --name single-node

# After loading images, restart deployment to use new image
kubectl rollout restart deployment user-service -n mbd
```

### Frontend (React + Vite)

```bash
# Customer frontend
cd frontend/customer-frontend
npm install
npm run dev          # Development server
npm run build        # Production build
npm run lint         # ESLint
docker build -t customer-frontend:latest .

# Admin frontend
cd frontend/admin-frontend
npm install
npm run dev
npm run build
npm run lint
docker build -t admin-frontend:latest .
```

### Kubernetes & Infrastructure

```bash
# Access Swagger UI for a service
kubectl port-forward svc/fund-service -n mbd 9080:8080
# Open http://localhost:9080/swagger-ui.html
# Also works for: portfolio-service, user-service, account-service, admin-service

# Access ArgoCD UI
kubectl port-forward svc/argocd-server -n argocd 8081:443
# Open https://localhost:8081

# Inspect PostgreSQL database
kubectl exec -it postgresql-0 -n mbd-infra -- psql -U mbdadmin -d mbd

# Check Kafka topics
kubectl exec -n mbd-infra kafka-0 -- /opt/kafka/bin/kafka-topics.sh --list --bootstrap-server localhost:9092
```

### Access URLs (via /etc/hosts → 127.0.0.1)

- Customer frontend: https://customer.mbd.local
- Admin frontend: https://admin.mbd.local
- Keycloak: https://keycloak.mbd.local
- Kafbat UI (Kafka browser): https://kafbat.mbd.local

### Kind Cluster Workflows

**CRITICAL for Kind clusters**: Docker images must be explicitly loaded into Kind. Images built on your host are NOT automatically available to the cluster.

```bash
# Complete workflow: Build → Load → Deploy
# 1. Build all backend services
cd backend
./gradlew build -x test
cd user-service && docker build -t user-service:latest . && cd ..
cd account-service && docker build -t account-service:latest . && cd ..
cd fund-service && docker build -t fund-service:latest . && cd ..
cd portfolio-service && docker build -t portfolio-service:latest . && cd ..
cd admin-service && docker build -t admin-service:latest . && cd ..
cd ..

# 2. Build frontends
cd frontend/customer-frontend && docker build -t customer-frontend:latest . && cd ../..
cd frontend/admin-frontend && docker build -t admin-frontend:latest . && cd ../..

# 3. Load ALL images into Kind (REQUIRED - pods will fail without this)
kind load docker-image \
  user-service:latest \
  account-service:latest \
  fund-service:latest \
  portfolio-service:latest \
  admin-service:latest \
  customer-frontend:latest \
  admin-frontend:latest \
  --name single-node

# 4. Restart deployments to pick up new images
kubectl rollout restart deployment -n mbd
```

**Single service rebuild** (for faster iteration):
```bash
# Example: Rebuild fund-service
cd backend/fund-service
docker build -t fund-service:latest .
kind load docker-image fund-service:latest --name single-node
kubectl rollout restart deployment fund-service -n mbd
kubectl logs -f -n mbd -l app=fund-service  # Watch logs
```

**Common Kind cluster issues**:
- `ErrImageNeverPull` → Image not loaded into Kind (run `kind load docker-image`)
- `ImagePullBackOff` → Same as above
- Service updated but pod still running old code → Forgot to restart deployment after loading image

## Architecture & Service Communication

### Backend Services (5 microservices)

All services run on port `:8080` inside the `mbd` namespace, communicate via **Feign clients with mTLS** (Istio DestinationRule: `ISTIO_MUTUAL`), and share a single **PostgreSQL database** (`mbd`) with per-service Flyway history tables.

1. **user-service** — User registration & profile (keyed by Keycloak `sub` claim)
   - Entity: `User` → table: `users`
   - Endpoints: `GET /api/users/profile`, `POST /api/users/register`, `GET /api/users/{id}`
   - No Feign clients (leaf service)

2. **account-service** — Investment accounts & transactions
   - Entities: `Account`, `Transaction` → tables: `accounts`, `transactions`
   - Feign: `UserClient` → user-service
   - Endpoints: `POST /api/accounts`, `POST /api/accounts/{id}/deposit`, `GET /api/accounts/{id}`, `GET /api/accounts/user/{userId}`, `GET /api/accounts/{id}/transactions`
   - Used by portfolio-service to debit (negative deposit) on BUY, credit on SELL

3. **fund-service** — Fund catalog, price updates, config consumer
   - Entity: `Fund` → table: `funds` (currentPrice, volatility, updateFrequencyMinutes)
   - Kafka producer: publishes `FundPriceUpdate` to `fund-price-updates` topic
   - Kafka consumer: listens to `config-updates` topic (from admin-service) and applies new volatility/frequency to all funds
   - Scheduler: `PriceUpdateScheduler` runs every 1 min, checks per-fund `updateFrequencyMinutes`, computes new price (random walk), saves, publishes to Kafka
   - Endpoints: `POST /api/funds`, `GET /api/funds`, `GET /api/funds/{id}`, `PUT /api/funds/{id}`, `DELETE /api/funds/{id}`, `PUT /api/funds/{id}/config`

4. **portfolio-service** — Holdings, portfolio value, price update consumer, value history
   - Entities: `Holding` (accountId, fundId, quantity, averagePrice, currentValue), `PortfolioValueSnapshot` (accountId, totalValue, timestamp)
   - Feign: `AccountClient`, `FundClient`
   - Kafka consumers (2 listeners on `fund-price-updates`):
     - `FundPriceConsumer` — updates holding.currentValue
     - `FundPriceUpdateConsumer` — updates holding.currentValue + creates `PortfolioValueSnapshot` for chart history
   - Endpoints: `GET /api/portfolio/{accountId}`, `GET /api/portfolio/{accountId}/history?limit=50`, `POST /api/portfolio/trade` (BUY/SELL)
   - Trade flow (BUY): fetch fund price → check balance → debit account → upsert holding (weighted avg price)
   - Trade flow (SELL): fetch fund price → check holding quantity → credit account → update/delete holding

5. **admin-service** — Admin-only config & monitoring (requires `admin` realm role)
   - Entity: `SystemConfig` → table: `system_config`
   - Security: Spring Security OAuth2 resource server, requires `hasRole('admin')` on `/api/admin/**`
   - Kafka producer: publishes `FundConfigDto` to `config-updates` topic when admin updates price-update config
   - Endpoints: `GET/PUT /api/admin/config/price-update`, `GET /api/admin/monitoring/system-health`, `GET /api/admin/monitoring/active-users`

### Shared Module

`backend/shared/` — Common DTOs used across services and frontends:
- `UserDto`, `RegistrationDto`
- `AccountDto`, `CreateAccountDto`, `DepositDto`
- `FundDto`, `FundConfigDto`, `FundPriceUpdate` (Kafka message)
- `PortfolioDto`, `TradeDto`, `HoldingDto`
- `PortfolioValueSnapshotDto`

### Dependency Graph

```
user-service ← account-service ← portfolio-service
                                     ↑ Feign (mTLS)
                                 fund-service
                                     ↑ Kafka (fund-price-updates)
                                 portfolio-service
                                     ↑ Kafka (config-updates)
                                 admin-service
```

### Kafka Topics (3 topics, all 3 partitions, RF 1)

1. **fund-price-updates** — `fund-service` (producer) → `portfolio-service` (2 consumers in same group)
2. **config-updates** — `admin-service` (producer) → `fund-service` (consumer)
3. **portfolio-updates** — reserved (currently unused; `PortfolioService.publishPortfolioUpdates` is a stub)

### Database Schema (shared `mbd` database)

| Table                     | Owner Service      | Key Columns / FKs                                              |
|---------------------------|--------------------|----------------------------------------------------------------|
| `users`                   | user-service       | `keycloak_id` (unique), `email` (unique)                       |
| `accounts`                | account-service    | `user_id` → `users(id)`, `account_number` (unique)             |
| `transactions`            | account-service    | `account_id` → `accounts(id)`                                  |
| `funds`                   | fund-service       | `isin` (unique), `current_price`, `volatility`, `update_frequency_minutes` |
| `holdings`                | portfolio-service  | `account_id` → `accounts`, `fund_id` → `funds`, unique(account_id, fund_id) |
| `portfolio_value_history` | portfolio-service  | `account_id`, `total_value`, `timestamp` (indexed on both)     |
| `system_config`           | admin-service      | `key` (unique), `value`                                        |

Each service uses **Flyway** with `baseline-on-migrate: true` and a per-service history table (e.g., `user_service_flyway_history`, `portfolio_service_flyway_history`) to avoid migration collisions.

## Authentication & Authorization

- **Frontend auth**: Both SPAs use `@react-keycloak/web` with Keycloak realm `mbd`, clients: `customer-frontend` (public, PKCE S256) and `admin-frontend` (public, requires `admin` realm role)
- **JWT validation**: Istio `RequestAuthentication` validates JWT against Keycloak JWKS endpoint (`http://keycloak.mbd-infra.svc.cluster.local:8080/realms/mbd/protocol/openid-connect/certs`)
- **Edge authorization**: Istio `AuthorizationPolicy` requires valid JWT principal for `/api/*` paths
- **Application-level security**: Only `admin-service` uses Spring Security OAuth2 resource server with role-based access control (`ROLE_admin` from `roles` claim)
- **user-service** parses JWT inline (extracts `sub` claim manually); does not use Spring Security

To create an admin user: register via customer frontend, then assign `admin` **realm role** in Keycloak admin console (`https://keycloak.mbd.local/admin`), then re-login.

## Istio Service Mesh & mTLS

- **Gateway**: `mbd-gateway` on ports 80 (redirect to HTTPS) and 443 (TLS via `mbd-tls-secret` from cert-manager)
- **VirtualServices**: Route by host + URI prefix (e.g., `customer.mbd.local/api/portfolio` → `portfolio-service:8080`)
- **PeerAuthentication**: STRICT mTLS in both `mbd` and `mbd-infra` namespaces (PERMISSIVE exception for Keycloak JWKS)
- **DestinationRules**: `ISTIO_MUTUAL` TLS for all `*.mbd.svc.cluster.local` and `*.mbd-infra.svc.cluster.local` traffic
- **cert-manager**: Self-signed CA → `mbd-ca` → `mbd-ca-issuer` (ClusterIssuer) → `mbd-tls-cert` (leaf cert for `*.mbd.local` hostnames)

## Frontend Architecture

### customer-frontend (https://customer.mbd.local)
- Pages: `Dashboard`, `Register`, `Funds`
- Dashboard: portfolio value, cash balance, holdings table with Sell action, **portfolio value history chart** (recharts `LineChart`, polls `/api/portfolio/{id}/history` every 60s)
- Funds page: lists funds with current price, Buy modal
- API client: `customerApi.ts` — calls user, account, fund, portfolio endpoints
- Axios interceptor: attaches Keycloak bearer token to every request
- Routes wrapped in `ProtectedRoute` (requires authenticated session)

### admin-frontend (https://admin.mbd.local)
- Pages: `Config` (price-update frequency/volatility), `AdminFunds` (CRUD funds + per-fund config)
- API client: `adminApi.ts` — calls admin config, monitoring, fund endpoints
- `ProtectedRoute` with `requiredRole="admin"` checks `keycloak.hasRealmRole('admin')`

## Key Implementation Details

### Feign Client Configuration
All Feign clients use `http://<service-name>.mbd.svc.cluster.local:8080` URLs (Istio sidecars handle mTLS transparently).

Example:
```kotlin
@FeignClient(name = "user-service", url = "http://user-service.mbd.svc.cluster.local:8080")
interface UserClient {
    @GetMapping("/api/users/{id}")
    fun getUser(@PathVariable id: Long): UserDto
}
```

### Kafka Configuration
Producer/consumer config in `application.yml`:
```yaml
spring:
  kafka:
    bootstrap-servers: kafka.mbd-infra.svc.cluster.local:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
    consumer:
      group-id: <service-name>
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.mbd.shared.dto
```

### Price Update Scheduler (fund-service)
- Runs every 1 minute (`@Scheduled(fixedRate = 60000)`)
- For each fund: checks if `fund.updatedAt + updateFrequencyMinutes` has passed
- Computes new price: random walk bounded by `volatility` (±volatility as max %)
- Saves to DB, publishes `FundPriceUpdate` to Kafka

### Portfolio Value History (portfolio-service)
- `FundPriceUpdateConsumer` creates a `PortfolioValueSnapshot` per affected account after updating holding values
- Frontend chart polls `GET /api/portfolio/{accountId}/history?limit=50` every 60s
- Snapshots stored in `portfolio_value_history` table (indexed on `account_id` and `timestamp`)

## Testing

### Integration Tests (Testcontainers)
- `PortfolioIntegrationTest` — validates Kafka consumption + DB updates (uses PostgreSQL + Kafka containers)
- `DatabaseMigrationTest` (user-service) — ensures Flyway scripts are valid

Run via:
```bash
cd backend
./gradlew :portfolio-service:test --tests "com.mbd.portfolio.PortfolioIntegrationTest"
./gradlew :user-service:test --tests "com.mbd.user.DatabaseMigrationTest"
```

**Known issue**: Testcontainers may fail locally on OrbStack with `docker-java` API version mismatch. Workaround: export `DOCKER_API_VERSION=1.40` and restart Gradle daemon with `./gradlew --stop`, or run tests in GitHub Actions (CI workflow at `.github/workflows/backend-integration-tests.yml`).

### Unit Tests
Standard JUnit 5 + Mockito tests in each service. Run all tests:
```bash
./gradlew test
```

## Important Architecture Notes

### Shared Database
All services share a single PostgreSQL database (`mbd`) with per-service Flyway history tables. This is a deliberate simplification for local development — production would use separate databases per service.

### Redundant Kafka Consumers
`portfolio-service` has **two** Kafka consumers on `fund-price-updates` with the same group ID:
- `FundPriceConsumer` — updates `holding.currentValue`
- `FundPriceUpdateConsumer` — updates `holding.currentValue` + creates `PortfolioValueSnapshot`

This is redundant (both update holdings). The second consumer is the one that also creates value snapshots for the chart.

### Security Defense-in-Depth
Only `admin-service` implements Spring Security OAuth2 resource server. Other services rely on Istio mesh authentication. This is acceptable for a demo but production should implement application-level security in all services (defense-in-depth).

## Security & Performance
- Always respect the directory boundaries given in a prompt.
- Never read files outside the requested service directory to save token budget.

### Keycloak Realm Roles
JWT tokens include a top-level `roles` claim (configured by a realm-role protocol mapper in Keycloak). `admin-service` reads this via `JwtGrantedAuthoritiesConverter` with claim name `roles` and prefix `ROLE_`.

## GitOps with ArgoCD

All infrastructure and application manifests in `infrastructure/` are managed by ArgoCD:
- **App-of-apps pattern**: `root-app.yaml` (in `infrastructure/`) points to `infrastructure/argocd/` which contains per-component `Application` manifests
- **Auto-sync enabled**: prune + selfHeal on all apps
- Per-component apps: namespaces, istio, cert-manager, postgresql, kafka, keycloak, kafbat-ui, and one app per backend service/frontend

Git is the single source of truth for cluster state.

## Additional Documentation

For more details, see:
- `README.md` — Project overview with screenshots and communication flows
- `doc/architecture.md` — Complete architecture documentation
- `doc/operation-notes.md` — Build, deploy, access operations
- `doc/backend-testing.md` — Testing guide with Testcontainers
- `doc/backend-services-implementation.md` — Step-by-step service implementation guide
- `doc/frontend-implementation.md` — Frontend implementation guide
- `infrastructure/k8s/istio/README.md` — Istio configuration details
- `infrastructure/k8s/cert-manager/README.md` — PKI and certificate management
