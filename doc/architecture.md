# MBD (My Bank Demo) — Architecture Overview

This document describes the current architecture of the MBD codebase. MBD is a demo investment-banking application built to gain experience with a specific tech stack: Spring Boot microservices in Kotlin, a service mesh with mTLS, Kafka event streaming, Keycloak SSO, cert-managed TLS, and a GitOps-driven local Kubernetes cluster. It is purely fictional and not affiliated with any real bank.

The repository is a **monorepo** with four top-level areas:

```
mbd/
├── backend/         # 5 Spring Boot (Kotlin) microservices + shared module
├── frontend/        # 2 React + Vite SPAs (customer, admin)
├── infrastructure/  # Kind, K8s manifests, Istio, ArgoCD apps
└── doc/             # Plans, implementation guides, architecture docs
```

---

## 1. High-Level Architecture

```
                       ┌──────────────────────────────────────────────────┐
                       │              Local Machine /etc/hosts             │
                       │  customer.mbd.local   admin.mbd.local             │
                       │  keycloak.mbd.local   kafbat.mbd.local            │
                       │  ────────────────────────────────────► Kind IP    │
                       └─────────────────────┬────────────────────────────┘
                                             │  HTTPS (cert-manager TLS)
                                             ▼
                       ┌──────────────────────────────────────────────────┐
                       │  Istio Ingress Gateway (mbd-gateway, :443)        │
                       │  TLS cert: mbd-tls-secret (cert-manager)          │
                       │  HTTP :80 → redirect to :443                      │
                       └─────────────────────┬────────────────────────────┘
                                             │  VirtualServices route by host + path
            ┌────────────────────────────────┼────────────────┬──────────────────────┐
            ▼                                ▼                ▼                      ▼
   customer-frontend (nginx)         admin-frontend (nginx)  keycloak (mbd-infra)   kafbat-ui (mbd-infra)
   customer.mbd.local                admin.mbd.local         keycloak.mbd.local     kafbat.mbd.local
            │                                │                ▲                      ▲
            │  Bearer JWT (Keycloak)         │  Bearer JWT    │ OIDC issuer          │ (public, no JWT)
            ▼                                ▼                │                      │
   ┌─────────────────────────────── mbd namespace (Istio-injected) ──────────────────────────────────┐
   │  RequestAuthentication (jwt-authn) + AuthorizationPolicy enforce JWT on /api/*                  │
   │                                                                                                  │
   │   user-service   account-service   fund-service   portfolio-service   admin-service              │
   │      :8080          :8080            :8080           :8080               :8080                   │
   │        │              │                │                │                   │                     │
   │        │  Feign       │  Feign         │  Kafka         │ Feign (account,   │ Kafka (config)      │
   │        │  (mTLS)      │  (mTLS)        │  producer      │  fund) + Kafka     │                     │
   │        ▼              ▼                ▼                ▼ consumer           ▼                     │
   │   ┌──────────────────────────────────────────────────────────────────────────────────────────┐  │
   │   │      PostgreSQL (shared DB `mbd`) in mbd-infra namespace, mTLS via DestinationRule        │  │
   │   └──────────────────────────────────────────────────────────────────────────────────────────┘  │
   └──────────────────────────────────────────────────────────────────────────────────────────────────┘
                                             │
                                             ▼
                       ┌──────────────────────────────────────────────────┐
                       │  mbd-infra namespace (Istio-injected)             │
                       │  postgresql, kafka (KRaft), keycloak,             │
                       │  keycloak-postgresql, kafbat-ui                    │
                       └──────────────────────────────────────────────────┘
```

Key characteristics:

- **Two namespaces**: `mbd` (application services + frontends) and `mbd-infra` (PostgreSQL, Kafka, Keycloak, Kafbat UI). Both have Istio sidecar injection enabled via the `istio-injection: enabled` namespace label.
- **Single shared PostgreSQL database** (`mbd`) for all backend services. Each service manages its own tables via **Flyway** with a per-service history table (e.g. `portfolio_service_flyway_history`) to avoid migration collisions.
- **mTLS everywhere** between mesh workloads via `PeerAuthentication` (STRICT in both namespaces, with a PERMISSIVE exception for Keycloak JWKS) and `DestinationRule`s using `ISTIO_MUTUAL`.
- **Kafka** in KRaft mode (no Zookeeper) carries three topics: `fund-price-updates`, `config-updates`, and `portfolio-updates`.
- **Keycloak** is the single identity provider for both frontends (SSO) and validates JWTs at the Istio mesh layer and inside `admin-service`.
- **cert-manager** acts as a private PKI, issuing the TLS certificate for the Istio ingress gateway so all four `*.mbd.local` hostnames are served over HTTPS.
- **Kafbat UI** provides a read-only web interface for inspecting Kafka topics and messages, exposed at `kafbat.mbd.local`.
- **ArgoCD** reconciles the cluster state from this Git repo (`github.com/edwinbulter/mbd`).

---

## 2. Backend Services

All backend services are **Spring Boot 3.1.0 + Kotlin 1.9.20**, targeting JDK 17, built with Gradle (Kotlin DSL). They share a `shared` Gradle module that contains common DTOs.

### 2.1 Shared module (`backend/shared`)

Holds the DTOs exchanged between services and with the frontends:

- `UserDto`, `RegistrationDto` — <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/shared/src/main/kotlin/com/mbd/shared/dto/UserDto.kt" />
- `AccountDto`, `CreateAccountDto`, `DepositDto` — <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/shared/src/main/kotlin/com/mbd/shared/dto/AccountDto.kt" />
- `FundDto`, `FundConfigDto`, `FundPriceUpdate` — <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/shared/src/main/kotlin/com/mbd/shared/dto/FundDto.kt" />
- `PortfolioDto`, `TradeDto`, `HoldingDto` — <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/shared/src/main/kotlin/com/mbd/shared/dto/PortfolioDto.kt" />
- `PortfolioValueSnapshotDto` — <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/shared/src/main/kotlin/com/mbd/shared/dto/PortfolioValueSnapshotDto.kt" />

`FundPriceUpdate` is the Kafka message payload published by `fund-service` and consumed by `portfolio-service`. `FundConfigDto` is the payload published by `admin-service` to the `config-updates` topic and consumed by `fund-service`.

### 2.2 user-service

User registration and profile lookup, keyed off the Keycloak `sub` claim.

- **Entity**: `User` (id, keycloakId, email, firstName, lastName, role, timestamps).
- **Table**: `users` (Flyway `V1__Create_Users_Table.sql`, `V2__Alter_Users_Id_To_Bigint.sql`).
- **Endpoints** (`/api/users`): `GET /profile` (extracts `sub` from the bearer JWT), `POST /register`, `GET /{id}`.
- **Notable**: JWT parsing is done inline (`extractKeycloakIdFromToken` in <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/user-service/src/main/kotlin/com/mbd/user/controller/UserController.kt" />); it does not use Spring Security OAuth2 resource server (unlike `admin-service`).
- **No Feign clients** — it is a leaf service that other services call.

### 2.3 account-service

Manages investment accounts and the money ledger (transactions).

- **Entities**: `Account` (userId, accountNumber, balance), `Transaction` (accountId, amount, type, description).
- **Tables**: `accounts`, `transactions` (FK to `accounts`, and `accounts.user_id` FK to `users`).
- **Feign client**: `UserClient` → `user-service` to validate the user when creating an account. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/account-service/src/main/kotlin/com/mbd/account/client/UserClient.kt" />
- **Endpoints** (`/api/accounts`):
  - `POST /` — create account (generates `MBD<uuid-prefix>` account number).
  - `POST /{accountId}/deposit` — add (or subtract, via negative amount) to balance and record a `Transaction` (type `DEPOSIT` for positive, `BUY_WITHDRAWAL` for negative).
  - `GET /{accountId}`, `GET /user/{userId}`, `GET /{accountId}/transactions`.
- **Used by**: `portfolio-service` calls `deposit` with a **negative amount** to debit the account when buying a fund, and with a **positive amount** to credit proceeds when selling.

### 2.4 fund-service

Catalog of funds, the source of price-update events, and a consumer of config-update events.

- **Entity**: `Fund` (name, isin, currentPrice, currency, volatility, updateFrequencyMinutes, timestamps).
- **Table**: `funds` (Flyway `V1__Create_Funds_Table.sql`).
- **Kafka producer**: `FundPriceProducer` publishes `FundPriceUpdate` to the `fund-price-updates` topic. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/fund-service/src/main/kotlin/com/mbd/fund/service/FundPriceProducer.kt" />
- **Kafka consumer**: `ConfigUpdateConsumer` listens on the `config-updates` topic and applies the new `volatility` and `updateFrequencyMinutes` to **all** funds. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/fund-service/src/main/kotlin/com/mbd/fund/service/ConfigUpdateConsumer.kt" />
- **Scheduler**: `PriceUpdateScheduler` runs every 1 minute (`@Scheduled(fixedRate = 60000)`). For each fund, it checks whether `fund.updatedAt + updateFrequencyMinutes` has passed; if so, it recomputes the price with a random walk bounded by `volatility`, persists it, and publishes a `FundPriceUpdate`. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/fund-service/src/main/kotlin/com/mbd/fund/service/PriceUpdateScheduler.kt" />
- **Endpoints** (`/api/funds`): `POST /`, `GET /`, `GET /{fundId}`, `PUT /{fundId}`, `DELETE /{fundId}`, `PUT /{fundId}/config` (update volatility + frequency for a single fund).
- **Application annotations**: `@EnableKafka`, `@EnableScheduling`.

### 2.5 portfolio-service

Holds fund positions per account, computes portfolio value, consumes price updates, and records portfolio value history snapshots.

- **Entities**: `Holding` (accountId, fundId, quantity, averagePrice, currentValue; unique on (accountId, fundId)), `PortfolioValueSnapshot` (accountId, totalValue, timestamp).
- **Tables**: `holdings` (FKs to `accounts` and `funds`), `portfolio_value_history` (indexed on account_id and timestamp).
- **Feign clients**: `AccountClient` (get account, debit/credit via deposit) and `FundClient` (get current price). <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/portfolio-service/src/main/kotlin/com/mbd/portfolio/client/AccountClient.kt" /> <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/portfolio-service/src/main/kotlin/com/mbd/portfolio/client/FundClient.kt" />
- **Kafka consumers** (two listeners on `fund-price-updates`, same consumer group `portfolio-service`):
  - `FundPriceConsumer` — updates `holding.currentValue = quantity * newPrice` for every matching holding, then calls `portfolioService.publishPortfolioUpdates` (currently a no-op stub). <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/portfolio-service/src/main/kotlin/com/mbd/portfolio/service/FundPriceConsumer.kt" />
  - `FundPriceUpdateConsumer` — also updates holding values, then creates a `PortfolioValueSnapshot` per affected account with the total portfolio value. This is what feeds the portfolio history chart in the frontend. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/portfolio-service/src/main/kotlin/com/mbd/portfolio/service/FundPriceUpdateConsumer.kt" />
- **Service**: `PortfolioService` implements:
  - `executeTrade` — handles both **BUY** and **SELL**:
    - **BUY**: fetch fund price → check balance → debit account (negative deposit) → upsert holding with weighted-average price.
    - **SELL**: fetch fund price → check holding quantity → credit account proceeds → update or delete holding.
  - `getPortfolio` — enriches holdings with fund name/ISIN via Feign and sums `currentValue`.
  - `getPortfolioHistory` — returns the most recent N `PortfolioValueSnapshot` records (default 50) for the chart.
  <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/portfolio-service/src/main/kotlin/com/mbd/portfolio/service/PortfolioService.kt" />
- **Endpoints** (`/api/portfolio`):
  - `GET /{accountId}` — returns `PortfolioDto` with holdings and total value.
  - `GET /{accountId}/history?limit=50` — returns `List<PortfolioValueSnapshotDto>` for the chart.
  - `POST /trade` — executes a BUY or SELL trade.
- **Application annotations**: `@EnableKafka`, `@EnableFeignClients`.

### 2.6 admin-service

Bank-employee-facing configuration and monitoring, the only service with Spring Security OAuth2 resource server enabled.

- **Entity**: `SystemConfig` (key, value, description).
- **Table**: `system_config`.
- **Security**: `SecurityConfig` requires authentication on everything and `hasRole('admin')` on `/api/admin/**`. JWT authorities are read from the `roles` claim with `ROLE_` prefix. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/admin-service/src/main/kotlin/com/mbd/admin/config/SecurityConfig.kt" />
- **Kafka producer**: `AdminConfigController` publishes `FundConfigDto` to the `config-updates` topic when the admin updates the price-update config.
- **Endpoints**:
  - `/api/admin/config/price-update` `GET`/`PUT` — read/update default `price_update_frequency_minutes` and `price_update_volatility` in `system_config`; on PUT, also publishes `FundConfigDto` to Kafka. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/admin-service/src/main/kotlin/com/mbd/admin/controller/AdminConfigController.kt" />
  - `/api/admin/monitoring/system-health`, `/api/admin/monitoring/active-users` — stub monitoring endpoints (active-users returns empty list). <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend/admin-service/src/main/kotlin/com/mbd/admin/controller/MonitoringController.kt" />
- **Application annotations**: `@EnableKafka`.

### 2.7 Backend dependency graph

```
user-service  ◄──── account-service  ◄──── portfolio-service
                                          ▲
                                          │ Feign (mTLS)
                                      fund-service
                                          ▲
                                          │ Kafka (fund-price-updates)
                                       portfolio-service
                                          ▲
                                          │ Kafka (config-updates)
                                      admin-service
                                          │
                          (independent; reads/writes system_config; admin role only)
```

- `account-service` → `user-service` (Feign, mTLS)
- `portfolio-service` → `account-service` and `fund-service` (Feign, mTLS)
- `fund-service` → Kafka `fund-price-updates` → `portfolio-service` (async price updates)
- `admin-service` → Kafka `config-updates` → `fund-service` (async config propagation)
- `admin-service` has no Feign clients; it only manages `system_config` and exposes monitoring. It is enforced as admin-only at both the Istio `AuthorizationPolicy` layer and Spring Security.

### 2.8 Database schema (shared `mbd` database)

| Table                     | Owner service      | Key columns / FKs                                              |
|---------------------------|--------------------|----------------------------------------------------------------|
| `users`                   | user-service       | `keycloak_id`, `email` (unique)                                |
| `accounts`                | account-service    | `user_id` → `users(id)`                                        |
| `transactions`            | account-service    | `account_id` → `accounts(id)`                                  |
| `funds`                   | fund-service       | `isin` (unique), `current_price`, `volatility`, `update_frequency_minutes` |
| `holdings`                | portfolio-service  | `account_id` → `accounts`, `fund_id` → `funds`, unique(account_id, fund_id) |
| `portfolio_value_history` | portfolio-service  | `account_id`, `total_value`, `timestamp` (indexed on both)     |
| `system_config`           | admin-service      | `key` (unique), `value`                                        |

Each service runs **Flyway** with `baseline-on-migrate: true`, `baseline-version: 0`, and a **service-specific history table** so multiple services can migrate the same DB without clobbering each other's history.

---

## 3. Frontends

Two independent React 18 + Vite + TypeScript SPAs, styled with TailwindCSS, authenticated via `keycloak-js` + `@react-keycloak/web`. Both are served by nginx in-container and rely on Istio for routing and API proxying.

### 3.1 customer-frontend (`https://customer.mbd.local`)

- **Keycloak client**: `customer-frontend` (public client, PKCE S256, `check-sso`).
- **Pages**: `Dashboard`, `Register`, `Funds`.
- **Dashboard** shows: total portfolio value, cash balance, number of holdings, a **portfolio value history chart** (recharts `LineChart`, polls `/api/portfolio/{id}/history` every 60s), and a holdings table with a Sell action per row.
- **Funds** page lists all funds with current price and a Buy modal.
- **API client**: `customerApi` calls `/api/users/*`, `/api/accounts/*`, `/api/funds`, `/api/portfolio/*` (same-origin; Istio routes `/api/*` to the right backend). <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/frontend/customer-frontend/src/services/customerApi.ts" />
- **Axios interceptor** attaches the Keycloak bearer token to every request.
- **Routes** are wrapped in `ProtectedRoute` (requires authenticated session). <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/frontend/customer-frontend/src/App.tsx" />
- **Key dependency**: `recharts` for the portfolio chart. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/frontend/customer-frontend/src/components/PortfolioChart.tsx" />

### 3.2 admin-frontend (`https://admin.mbd.local`)

- **Keycloak client**: `admin-frontend` (public client).
- **Pages**: `Config` (price-update frequency/volatility), `AdminFunds` (CRUD funds + per-fund config).
- **API client**: `adminApi` calls `/api/admin/config/*`, `/api/admin/monitoring/*`, and `/api/funds/*`. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/frontend/admin-frontend/src/services/adminApi.ts" />
- **`ProtectedRoute`** accepts a `requiredRole="admin"` and checks `keycloak.hasRealmRole('admin')`, redirecting to `/unauthorized` otherwise.

### 3.3 SSO flow

1. User visits `customer.mbd.local` or `admin.mbd.local`.
2. `ReactKeycloakProvider` (init `check-sso`, PKCE S256) redirects to `https://keycloak.mbd.local/realms/mbd` if not authenticated.
3. Keycloak authenticates and redirects back with an access token containing a top-level `roles` claim (configured by a realm-role protocol mapper in `configure-realm.sh`).
4. The SPA stores the token and attaches it as `Authorization: Bearer ...` on API calls.
5. Istio's `RequestAuthentication` (jwt-authn) validates the JWT against Keycloak's JWKS endpoint; `AuthorizationPolicy` allows `/api/*` only for requests with a valid principal, and `admin-service-policy` further restricts `/api/admin/*`.
6. `admin-service` additionally enforces `hasRole('admin')` in Spring Security.

To make a user an admin: register normally, then assign the `admin` **realm role** in the Keycloak admin console and re-login (see `doc/operation-notes.md`).

---

## 4. Infrastructure

### 4.1 Local Kubernetes — Kind

`infrastructure/kind/config.yaml` defines a Kind cluster with one control-plane node (mapping host ports 80/443) and two worker nodes. The control-plane is labeled `ingress-ready=true` so the Istio ingress gateway can bind to the host.

### 4.2 Namespaces and policies

- `infrastructure/k8s/namespaces.yaml` creates `mbd` and `mbd-infra`, both labeled `istio-injection: enabled`.
- `network-policy.yaml` restricts `mbd` namespace ingress/egress to `mbd`, `mbd-infra`, `istio-system`, plus DNS (53) to anywhere.
- `resource-quota.yaml` caps the `mbd` namespace at 8 CPU / 16Gi requested, 24 CPU / 32Gi limited, 20 PVCs.
- `allow-app-to-postgres.yaml` is an Istio `AuthorizationPolicy` permitting pods from namespace `mbd` to reach the `postgresql` pod in `mbd-infra`.

### 4.3 Istio service mesh

Located in `infrastructure/k8s/istio/` (see also <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/infrastructure/k8s/istio/README.md" />):

- **`gateway.yaml`** — `mbd-gateway` on port 80 (redirect to HTTPS) and 443 (SIMPLE TLS using `mbd-tls-secret`), for hosts `customer.mbd.local`, `admin.mbd.local`, `keycloak.mbd.local`, `kafbat.mbd.local`. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/infrastructure/k8s/istio/gateway.yaml" />
- **`peer-authentication.yaml`** — STRICT mTLS in both `mbd` and `mbd-infra`; PERMISSIVE for the `keycloak` pod (so JWKS can be fetched over plaintext inside the mesh). <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/infrastructure/k8s/istio/peer-authentication.yaml" />
- **`request-authentication.yaml`** — validates Keycloak JWTs for all pods in `mbd`; `forwardOriginalToken: true` so backends still receive the bearer. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/infrastructure/k8s/istio/request-authentication.yaml" />
- **`authorization-policy.yaml`** — four policies:
  - `api-access-policy`: allows service-to-service traffic from `cluster.local/ns/mbd/*` to `/api/*`, and external JWT-bearing requests to user/account/fund/portfolio paths.
  - `admin-service-policy`: requires a valid JWT principal for `/api/admin/*` on the `admin-service` pod.
  - `customer-frontend-public` / `admin-frontend-public`: allow `/*` to the nginx frontends (no JWT needed for static assets).
  <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/infrastructure/k8s/istio/authorization-policy.yaml" />
- **`destination-rules.yaml`** — `ISTIO_MUTUAL` TLS for `*.mbd.svc.cluster.local`, `*.mbd-infra.svc.cluster.local`, and cross-namespace `postgresql.mbd-infra.svc.cluster.local`. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/infrastructure/k8s/istio/destination-rules.yaml" />
- **VirtualServices** (`*-vs.yaml`) — route by host + URI prefix to each backend (with CORS allowing the two frontend origins) and to the frontends (catch-all on their respective hosts). The frontend VirtualServices are prefixed `z-` so they sort after the API VirtualServices and don't shadow `/api/*`.

### 4.4 PostgreSQL

`infrastructure/k8s/postgresql/` — a single-replica `StatefulSet` running `postgres:15-alpine` in `mbd-infra`, backed by a PVC, exposed via `postgresql.mbd-infra.svc.cluster.local:5432`. Credentials come from the `postgresql-secret` (created by `infrastructure/scripts/create-dev-secrets.sh`: user `mbdadmin`, db `mbd`, password `mbdpassword`).

### 4.5 Kafka (KRaft)

`infrastructure/k8s/kafka/` — single-broker `apache/kafka:3.7.0` in KRaft mode (combined broker+controller, no Zookeeper). Config in `configmap.yaml` advertises `kafka.mbd-infra.svc.cluster.local:9092`. `create-topics-job.yaml` provisions:

- `fund-price-updates` (3 partitions, RF 1) — produced by `fund-service`, consumed by `portfolio-service`.
- `config-updates` (3 partitions, RF 1) — produced by `admin-service`, consumed by `fund-service`.
- `portfolio-updates` (3 partitions, RF 1) — reserved for downstream/portfolio notifications (currently unused; `PortfolioService.publishPortfolioUpdates` is a stub).

### 4.6 Kafbat UI

`infrastructure/k8s/kafbat-ui/` — `ghcr.io/kafbat/kafka-ui:latest` in `mbd-infra`, exposed via `kafbat-ui.mbd-infra.svc.cluster.local:8080` and externally at `https://kafbat.mbd.local` through `mbd-gateway` + a `VirtualService`. An `AuthorizationPolicy` (`kafbat-ui-public`) allows `/*` without JWT — the UI is publicly accessible. <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/infrastructure/k8s/kafbat-ui/virtualservice.yaml" />

### 4.7 Keycloak

`infrastructure/k8s/keycloak/` — `quay.io/keycloak/keycloak:23.0` in `mbd-infra`, backed by its own `keycloak-postgresql` StatefulSet, exposed via `keycloak.mbd-infra.svc.cluster.local:8080` and externally at `https://keycloak.mbd.local` through `mbd-gateway` + a `VirtualService`. `KC_PROXY=edge` lets Istio terminate TLS.

`configure-realm.sh` bootstraps the `mbd` realm:

- Realm roles: `customer`, `admin`.
- Clients: `mbd-backend` (confidential, service accounts), `customer-frontend` and `admin-frontend` (public, PKCE, redirect URIs on their respective hostnames).
- A `roles` client scope with an `oidc-usermodel-realm-role-mapper` so realm roles appear as a top-level `roles` claim in access tokens — this is what both Istio's `RequestAuthentication` and `admin-service`'s `JwtGrantedAuthoritiesConverter` (claim name `roles`, prefix `ROLE_`) rely on.

### 4.8 cert-manager (PKI)

`infrastructure/k8s/cert-manager/` (see also <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/infrastructure/k8s/cert-manager/README.md" />) — a three-tier PKI:

1. `selfsigned-issuer` (self-signed `ClusterIssuer`) — bootstrap issuer.
2. `mbd-ca` (`Certificate`, `isCA: true`) — the MBD root CA, stored in `mbd-ca-secret` in `cert-manager`.
3. `mbd-ca-issuer` (`ClusterIssuer`, CA type) — signs leaf certs using `mbd-ca-secret`.
4. `mbd-tls-cert` (`Certificate` in `istio-system`) — leaf cert for `customer.mbd.local`, `admin.mbd.local`, `keycloak.mbd.local`, `kafbat.mbd.local`, stored as `mbd-tls-secret`.

The Istio gateway references `mbd-tls-secret` for HTTPS. cert-manager auto-renews the certificate before expiry.

Note: cert-manager is **only** used for the ingress gateway TLS. The mTLS certificates for service-to-service communication inside the mesh are managed by **Istio's own internal CA** (not cert-manager).

### 4.9 ArgoCD (GitOps)

`infrastructure/argocd/` contains one `Application` per component, all pointing at `git@github.com:edwinbulter/mbd.git` `main`, with `automated.prune + selfHeal`:

- `root-app.yaml` (in `infrastructure/`) is the **app-of-apps**: it points ArgoCD at `infrastructure/argocd/` (non-recursive), which in turn contains all the per-component `Application` manifests.
- `project.yaml` defines the `mbd` `AppProject` scoped to the `mbd` and `mbd-infra` destinations.
- Per-component apps: `namespaces-app`, `istio-app`, `cert-manager-app`, `postgresql-app`, `kafka-app`, `keycloak-app`, `kafbat-ui-app`, and one per backend service / frontend (`user-service-app`, `account-service-app`, `fund-service-app`, `portfolio-service-app`, `admin-service-app`, `customer-frontend-app`, `admin-frontend-app`).

ArgoCD manages **application deployments only** (not the Kind cluster itself or Istio/cert-manager/ArgoCD installations, per the plan in `doc/plan-1.md`).

---

## 5. Build, Deploy, and Operate

### 5.1 Backend build & image load

```bash
cd backend
./gradlew :user-service:bootJar          # or :build for all
docker build --no-cache -t user-service:latest -f user-service/Dockerfile .
# Kind only:
kind load docker-image user-service:latest --name mbd
```

Each service `Dockerfile` is `eclipse-temurin:17-jre` + the built `*-SNAPSHOT.jar`. ArgoCD then re-syncs the `Deployment` (which uses `imagePullPolicy: Never` for Kind-loaded images).

### 5.2 Frontend build & image load

```bash
cd frontend/customer-frontend
npm install && npm run build
docker build -t customer-frontend:latest .
# Same for admin-frontend, then kind load docker-image ...
```

The frontend image is a multi-stage build: `node:20-alpine` builds Vite, then `nginx:stable-alpine` serves `dist/`. `nginx.conf` falls back to `index.html` for React Router paths and exposes `/health` for probes.

### 5.3 Secrets

`infrastructure/scripts/create-dev-secrets.sh` creates the dev `postgresql-secret` (in both namespaces) and `keycloak-secret` in `mbd-infra`. These are dev-only plaintext secrets; `DB_PASSWORD` is injected into each backend `Deployment` from `postgresql-secret`.

### 5.4 Access

Add the Istio ingress gateway external IP to `/etc/hosts` for `customer.mbd.local`, `admin.mbd.local`, `keycloak.mbd.local`, `kafbat.mbd.local`. Then:

- Customer frontend: `https://customer.mbd.local`
- Admin frontend: `https://admin.mbd.local`
- Keycloak admin console: `https://keycloak.mbd.local/admin`
- Kafbat UI: `https://kafbat.mbd.local`

Fallback port-forwards (bypassing the gateway) are documented in `doc/operation-notes.md` (ArgoCD `8081`, Kafka `9092`, Keycloak `8082`).

### 5.5 Making a user an admin

1. Register a user through the customer frontend.
2. In the Keycloak admin console, assign the `admin` **realm role** to that user.
3. Log out and back in to the admin frontend so the new role is in the access token.

---

## 6. End-to-End Request Flow Examples

### 6.1 Customer buys a fund (`POST /api/portfolio/trade`)

1. `customer-frontend` calls `customerApi.buyFund` → axios attaches the Keycloak bearer token → `POST https://customer.mbd.local/api/portfolio/trade`.
2. Istio `mbd-gateway` terminates TLS, `portfolio-service-vs` matches `/api/portfolio` prefix and routes to `portfolio-service:8080` (mTLS via `DestinationRule`).
3. `RequestAuthentication` validates the JWT against Keycloak JWKS; `api-access-policy` allows the request (valid principal).
4. `PortfolioController.executeTrade` → `PortfolioService.executeTrade` (BUY):
   - `FundClient.getFund(fundId)` over Feign/mTLS to `fund-service` → returns current price.
   - `AccountClient.getAccount(accountId)` over Feign/mTLS to `account-service` → checks balance.
   - `AccountClient.updateBalance(accountId, DepositDto(-totalCost))` debits the account; `account-service` records a `BUY_WITHDRAWAL` transaction.
   - Upserts the `Holding` (weighted-average price) and returns `HoldingDto`.
5. Later, `PriceUpdateScheduler` in `fund-service` ticks (every 1 min), checks per-fund `updateFrequencyMinutes`, updates `funds.current_price`, and publishes `FundPriceUpdate` to Kafka.
6. Both `FundPriceConsumer` and `FundPriceUpdateConsumer` in `portfolio-service` consume it:
   - `FundPriceConsumer` updates `holdings.current_value`.
   - `FundPriceUpdateConsumer` updates `holdings.current_value` and creates a `PortfolioValueSnapshot` per affected account.
7. The next `GET /api/portfolio/{accountId}` reflects the new total value; the frontend chart polls `GET /api/portfolio/{accountId}/history` every 60s and picks up the new snapshot.

### 6.2 Customer sells a holding (`POST /api/portfolio/trade`)

1. Same routing and JWT validation as above.
2. `PortfolioService.executeTrade` (SELL):
   - `FundClient.getFund(fundId)` → returns current price.
   - Checks `holding.quantity >= sellQuantity`.
   - `AccountClient.updateBalance(accountId, DepositDto(+proceeds))` credits the account; `account-service` records a `DEPOSIT` transaction.
   - Updates or deletes the holding (if quantity reaches zero).
3. Response flows back through sidecars + gateway → browser.

### 6.3 Admin changes price-update config (`PUT /api/admin/config/price-update`)

1. `admin-frontend` → `https://admin.mbd.local/api/admin/config/price-update` with an admin-role JWT.
2. Gateway → `admin-service-vs` → `admin-service:8080`.
3. Istio `admin-service-policy` requires a valid JWT principal; Spring Security `SecurityConfig` requires `ROLE_admin` (from the `roles` claim).
4. `AdminConfigController.updatePriceUpdateConfig` upserts `system_config` rows for `price_update_frequency_minutes` and `price_update_volatility`.
5. The controller publishes `FundConfigDto` to the `config-updates` Kafka topic.
6. `ConfigUpdateConsumer` in `fund-service` consumes it and applies the new `volatility` and `updateFrequencyMinutes` to **all** funds in the database.
7. The next `PriceUpdateScheduler` tick uses the updated per-fund config.

---

## 7. Kafka Topic Flow

```
                    ┌─────────────────┐
                    │   admin-service │
                    └────────┬────────┘
                             │ PUT /api/admin/config/price-update
                             │ → kafkaTemplate.send("config-updates", config)
                             ▼
                    ┌─────────────────┐
                    │  config-updates │  (3 partitions, RF 1)
                    └────────┬────────┘
                             │ @KafkaListener
                             ▼
                    ┌─────────────────┐
                    │   fund-service  │
                    │ ConfigUpdate    │
                    │ Consumer        │
                    │ → updates all   │
                    │   funds         │
                    └─────────────────┘

                    ┌─────────────────┐
                    │   fund-service  │
                    │ PriceUpdate     │
                    │ Scheduler       │
                    │ (every 1 min)   │
                    └────────┬────────┘
                             │ kafkaTemplate.send("fund-price-updates", update)
                             ▼
                    ┌─────────────────────┐
                    │ fund-price-updates  │  (3 partitions, RF 1)
                    └────────┬────────────┘
                             │
                ┌────────────┴────────────┐
                │ @KafkaListener           │ @KafkaListener
                ▼                          ▼
       ┌──────────────────┐      ┌──────────────────────┐
       │ FundPriceConsumer│      │ FundPriceUpdate      │
       │ (portfolio-svc)  │      │ Consumer             │
       │ → updates        │      │ (portfolio-svc)      │
       │   holding.value  │      │ → updates holding    │
       │                  │      │   + creates snapshot │
       └──────────────────┘      └──────────────────────┘

       ┌─────────────────┐
       │ portfolio-svc   │  (stub — publishPortfolioUpdates is a no-op)
       └────────┬────────┘
                │ (reserved)
                ▼
       ┌─────────────────────┐
       │ portfolio-updates   │  (3 partitions, RF 1, currently unused)
       └─────────────────────┘
```

---

## 8. Current State and Caveats

- This is an **MVP/learning project**. Notable simplifications:
  - `user-service` parses the JWT inline rather than using Spring Security OAuth2 resource server (only `admin-service` does).
  - `PortfolioService.publishPortfolioUpdates` and the `portfolio-updates` Kafka topic are stubs (no downstream consumers).
  - `MonitoringController.getActiveUsers` returns an empty list.
  - Dev secrets are plaintext in `create-dev-secrets.sh`; not production-grade.
  - `portfolio-service` has two Kafka consumers on the same topic (`fund-price-updates`) with the same group ID — both update holdings, which is redundant. The `FundPriceUpdateConsumer` is the one that also creates portfolio value snapshots.
- The shared database with per-service Flyway history tables is a deliberate simplification for a local Kind cluster; a production system would use separate databases per service.
- Kafbat UI is publicly accessible (no JWT required) — acceptable for a local dev cluster, not for production.

---

## 9. Changes Since architecture-1.md

| Area | What changed |
|------|-------------|
| **Kafbat UI** | New component in `mbd-infra`, exposed at `kafbat.mbd.local`. Added to gateway, cert, and ArgoCD. |
| **Gateway** | Now serves 4 hosts (added `kafbat.mbd.local`). |
| **cert-manager** | `mbd-tls-certificate.yaml` now includes `kafbat.mbd.local` as a 4th DNS name. |
| **portfolio-service** | New `PortfolioValueSnapshot` entity + `portfolio_value_history` table. New `FundPriceUpdateConsumer` that creates value snapshots. New `GET /{accountId}/history` endpoint. **SELL trades now implemented** in `executeSell`. |
| **fund-service** | `PriceUpdateScheduler` now runs every 1 min (was 5 min) and uses per-fund `updateFrequencyMinutes` check. New `ConfigUpdateConsumer` on `config-updates` topic. |
| **admin-service** | `AdminConfigController` now publishes `FundConfigDto` to `config-updates` Kafka topic on config update (was just storing in DB). |
| **Kafka** | `config-updates` topic is now actively used (was reserved). |
| **Frontend** | `customer-frontend` has a new `PortfolioChart` component (recharts) that polls portfolio history. `sellFund` API method added. `adminApi.updateFundConfig` added. |
| **Docs** | New `README.md` and `README-NL.md` at project root. New `README.md`/`README-NL.md` in `istio/` and `cert-manager/` folders. |

---

## 10. Production Considerations & Future Work

While MBD is a fully functional demonstration of a cloud-native investment platform, certain architectural simplifications were made for ease of local development and demonstration. In a mission-critical production environment, the following improvements would be implemented:

### 10.1 Database Isolation (Database-per-Service)
Currently, all microservices share a single PostgreSQL database instance and the same physical `mbd` database, using separate Flyway history tables to avoid collisions.
- **Production Approach**: Each service should have its own dedicated database instance (or at least a separate logical database/schema with independent credentials). This ensures that a failure or maintenance window for one service's database doesn't impact others, and allows for independent scaling and schema evolution.

### 10.2 Application-Level Security (Defense in Depth)
Presently, only the `admin-service` implements Spring Security OAuth2/JWT validation. Other services rely on the Istio service mesh for edge authentication.
- **Production Approach**: Every microservice should implement Spring Security. Relying solely on the service mesh is a "hard shell, soft center" approach. Application-level validation provides defense in depth, ensuring that even if the mesh is bypassed (e.g., via a compromised pod within the namespace), data remains protected by role-based access control (RBAC).

### 10.3 Advanced Observability & Distributed Tracing
MBD uses Spring Boot Actuator for basic health and metrics.
- **Production Approach**:
  - **Distributed Tracing**: Implement Micrometer Tracing with Jaeger or Zipkin to track requests as they traverse service boundaries (e.g., following a "Buy" order from the Gateway through the Portfolio service into Kafka and the Fund service).
  - **Log Aggregation**: Centralize logs using an ELK (Elasticsearch, Logstash, Kibana) or PLG (Promtail, Loki, Grafana) stack for easier cross-service debugging.

### 10.4 Kafka Resilience
The current setup uses a single-node Kafka broker without complex error handling.
- **Production Approach**:
  - Use a multi-broker cluster with a replication factor of at least 3.
  - Implement **Dead Letter Queues (DLQ)** to handle messages that consistently fail processing, preventing them from blocking the event stream.
  - Enable idempotent producers and `acks=all` for guaranteed message delivery.

### 10.5 Deployment Pipelines (CI/CD)
ArgoCD handles the deployment (CD) based on Git state.
- **Production Approach**: Implement a robust Continuous Integration (CI) pipeline (e.g., GitHub Actions or Azure DevOps) that runs the Testcontainers integration suite, performs Static Application Security Testing (SAST), and only pushes Docker images to the registry after all quality gates are passed.

---

## 11. Where to Look Next

- Original requirements and decisions: <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/doc/plan-1.md" />
- Backend implementation guide: <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/doc/backend-services-implementation.md" />
- Frontend implementation guide: <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/doc/frontend-implementation.md" />
- Build/run operations: <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/doc/operation-notes.md" />
- Backend testing: <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/backend-testing.md" />
- Istio configuration: <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/infrastructure/k8s/istio/README.md" />
- cert-manager PKI: <ref_file file="/Users/e.g.h.bulter/IdeaProjects/mbd/infrastructure/k8s/cert-manager/README.md" />
- Infrastructure step-by-step: `doc/infrastructure/00-bootstrap-cluster.md` … `07-cert-manager-setup.md`.
