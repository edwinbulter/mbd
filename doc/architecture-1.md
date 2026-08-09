# MBD (My Bank Demo) — Architecture Overview

This document describes the current architecture, services, functionality, and integration of the MBD codebase. MBD is a demo banking/investment application (a "competitor of Brandnewday") built to gain experience with a specific tech stack: Spring Boot microservices in Kotlin, a service mesh with mTLS, Kafka event streaming, Keycloak SSO, and a GitOps-driven local Kubernetes cluster.

The repository is a **monorepo** with three top-level areas:

```
mbd/
├── backend/         # 5 Spring Boot (Kotlin) microservices + shared module
├── frontend/        # 2 React + Vite SPAs (customer, admin)
├── infrastructure/  # Kind, K8s manifests, Istio, ArgoCD apps
└── doc/             # Plans, implementation guides, this file
```

---

## 1. High-Level Architecture

```
                       ┌────────────────────────────────────────────┐
                       │              Local Machine /etc/hosts       │
                       │  customer.mbd.local  admin.mbd.local        │
                       │  keycloak.mbd.local   -> Kind Ingress IP    │
                       └─────────────────────┬──────────────────────┘
                                             │  HTTPS (cert-manager TLS)
                                             ▼
                       ┌────────────────────────────────────────────┐
                       │  Istio Ingress Gateway (mbd-gateway, :443)  │
                       │  TLS cert: mbd-tls-secret (cert-manager)    │
                       └─────────────────────┬──────────────────────┘
                                             │  VirtualServices route by host + path
            ┌────────────────────────────────┼─────────────────────────────────┐
            ▼                                ▼                                 ▼
   customer-frontend (nginx)         admin-frontend (nginx)            keycloak (mbd-infra)
   host: customer.mbd.local          host: admin.mbd.local             host: keycloak.mbd.local
            │                                │                                 │
            │  Bearer JWT (Keycloak)         │  Bearer JWT (admin role)        │ OIDC issuer
            ▼                                ▼                                 ▼
   ┌─────────────────────────────── mbd namespace (Istio-injected) ──────────────────────┐
   │  RequestAuthentication (jwt-authn) + AuthorizationPolicy enforce JWT on /api/*      │
   │                                                                                      │
   │   user-service   account-service   fund-service   portfolio-service   admin-service  │
   │      :8080          :8080            :8080           :8080               :8080       │
   │        │              │                │                │                   │         │
   │        │  Feign       │  Feign         │  Kafka         │ Feign (account,   │         │
   │        │  (mTLS)      │  (mTLS)        │  producer      │  fund) + Kafka     │         │
   │        ▼              ▼                ▼                ▼ consumer           ▼         │
   │   ┌──────────────────────────────────────────────────────────────────────────────┐  │
   │   │      PostgreSQL (shared DB `mbd`) in mbd-infra namespace, mTLS via DestRule    │  │
   │   └──────────────────────────────────────────────────────────────────────────────┘  │
   └──────────────────────────────────────────────────────────────────────────────────────┘
                                             │
                                             ▼
                       ┌────────────────────────────────────────────┐
                       │  mbd-infra namespace (Istio-injected)       │
                       │  postgresql, kafka (KRaft), keycloak,       │
                       │  keycloak-postgresql                        │
                       └────────────────────────────────────────────┘
```

Key characteristics:

- **Two namespaces**: `mbd` (application services + frontends) and `mbd-infra` (PostgreSQL, Kafka, Keycloak). Both have Istio sidecar injection enabled.
- **Single shared PostgreSQL database** (`mbd`) for all backend services. Each service manages its own tables via **Flyway** with a per-service history table (e.g. `account_service_flyway_history`) to avoid migration collisions.
- **mTLS everywhere** between mesh workloads via `PeerAuthentication` (STRICT in both namespaces, with a PERMISSIVE exception for Keycloak JWKS) and `DestinationRule`s using `ISTIO_MUTUAL`.
- **Kafka** in KRaft mode (no Zookeeper) carries fund price updates from `fund-service` to `portfolio-service`.
- **Keycloak** is the single identity provider for both frontends (SSO) and validates JWTs at the Istio mesh layer and inside `admin-service`.
- **ArgoCD** reconciles the cluster state from this Git repo (`github.com/edwinbulter/mbd`).

---

## 2. Backend Services

All backend services are **Spring Boot 3.1.0 + Kotlin 1.9.20**, targeting JDK 17, built with Gradle (Kotlin DSL). They share a `shared` Gradle module that contains common DTOs.

### 2.1 Shared module (`backend/shared`)

Holds the DTOs exchanged between services and with the frontends:

- `UserDto`, `RegistrationDto` — [UserDto](/backend/shared/src/main/kotlin/com/mbd/shared/dto/UserDto.kt)
- `AccountDto`, `CreateAccountDto`, `DepositDto` — [AccountDto](/backend/shared/src/main/kotlin/com/mbd/shared/dto/AccountDto.kt)
- `FundDto`, `FundConfigDto`, `FundPriceUpdate` — [FundDto](/backend/shared/src/main/kotlin/com/mbd/shared/dto/FundDto.kt)
- `PortfolioDto`, `TradeDto`, `HoldingDto` — [PortfolioDto](/backend/shared/src/main/kotlin/com/mbd/shared/dto/PortfolioDto.kt)

`FundPriceUpdate` is the Kafka message payload published by `fund-service` and consumed by `portfolio-service`.

### 2.2 user-service

User registration and profile lookup, keyed off the Keycloak `sub` claim.

- **Entity**: `User` (id, keycloakId, email, firstName, lastName, role, timestamps).
- **Table**: `users` (Flyway `V1__Create_Users_Table.sql`, `V2__Alter_Users_Id_To_Bigint.sql`).
- **Endpoints** (`/api/users`): `GET /profile` (extracts `sub` from the bearer JWT), `POST /register`, `GET /{id}`.
- **Notable**: JWT parsing is done inline (`extractKeycloakIdFromToken` in [UserController](/backend/user-service/src/main/kotlin/com/mbd/user/controller/UserController.kt)); it does not use Spring Security OAuth2 resource server (unlike `admin-service`).
- **No Feign clients** — it is a leaf service that other services call.

### 2.3 account-service

Manages investment accounts and the money ledger (transactions).

- **Entities**: `Account` (userId, accountNumber, balance), `Transaction` (accountId, amount, type, description).
- **Tables**: `accounts`, `transactions` (FK to `accounts`, and `accounts.user_id` FK to `users`).
- **Feign client**: `UserClient` → `user-service` to validate the user when creating an account. [UserClient](/backend/account-service/src/main/kotlin/com/mbd/account/client/UserClient.kt)
- **Endpoints** (`/api/accounts`):
  - `POST /` — create account (generates `MBD<uuid-prefix>` account number).
  - `POST /{accountId}/deposit` — add (or subtract, via negative amount) to balance and record a `Transaction`.
  - `GET /{accountId}`, `GET /user/{userId}`, `GET /{accountId}/transactions`.
- **Used by**: `portfolio-service` calls `deposit` with a **negative amount** to debit the account when buying a fund.

### 2.4 fund-service

Catalog of funds and the source of price-update events.

- **Entity**: `Fund` (name, isin, currentPrice, currency, volatility, updateFrequencyMinutes).
- **Table**: `funds`.
- **Kafka producer**: `FundPriceProducer` publishes `FundPriceUpdate` to the `fund-price-updates` topic. [FundPriceProducer](/backend/fund-service/src/main/kotlin/com/mbd/fund/service/FundPriceProducer.kt)
- **Scheduler**: `PriceUpdateScheduler` runs every 5 minutes (`@Scheduled(fixedRate = 300000)`), recomputes each fund price with a random walk bounded by `volatility`, persists it, and publishes the update. [PriceUpdateScheduler](/backend/fund-service/src/main/kotlin/com/mbd/fund/service/PriceUpdateScheduler.kt)
- **Endpoints** (`/api/funds`): `POST /`, `GET /`, `GET /{fundId}`, `PUT /{fundId}`, `DELETE /{fundId}`, `PUT /{fundId}/config` (update volatility + frequency).
- **Note**: The scheduler's fixed rate is hardcoded; the admin-configurable frequency/volatility lives in `admin-service`/`system_config` and is intended to feed fund-level config via `PUT /api/funds/{id}/config`.

### 2.5 portfolio-service

Holds fund positions per account and computes portfolio value; consumes price updates.

- **Entity**: `Holding` (accountId, fundId, quantity, averagePrice, currentValue; unique on (accountId, fundId)).
- **Table**: `holdings` (FKs to `accounts` and `funds`).
- **Feign clients**: `AccountClient` (get account, debit via deposit) and `FundClient` (get current price). [AccountClient](/backend/portfolio-service/src/main/kotlin/com/mbd/portfolio/client/AccountClient.kt) [FundClient](/backend/portfolio-service/src/main/kotlin/com/mbd/portfolio/client/FundClient.kt)
- **Kafka consumer**: `FundPriceConsumer` listens on `fund-price-updates` and recomputes `currentValue = quantity * newPrice` for every matching holding. [FundPriceConsumer](/backend/portfolio-service/src/main/kotlin/com/mbd/portfolio/service/FundPriceConsumer.kt)
- **Service**: `PortfolioService.executeTrade` implements the BUY flow: fetch fund price → check balance → debit account (negative deposit) → upsert holding with weighted-average price. `getPortfolio` enriches holdings with fund name/ISIN and sums `currentValue`. [PortfolioService](/backend/portfolio-service/src/main/kotlin/com/mbd/portfolio/service/PortfolioService.kt)
- **Endpoints** (`/api/portfolio`): `GET /{accountId}` (returns `PortfolioDto` with total value), `POST /trade` (BUY only in MVP).

### 2.6 admin-service

Bank-employee-facing configuration and monitoring, the only service with Spring Security OAuth2 resource server enabled.

- **Entity**: `SystemConfig` (key, value, description).
- **Table**: `system_config`.
- **Security**: `SecurityConfig` requires authentication on everything and `hasRole('admin')` on `/api/admin/**`. JWT authorities are read from the `roles` claim with `ROLE_` prefix. [SecurityConfig](/backend/admin-service/src/main/kotlin/com/mbd/admin/config/SecurityConfig.kt)
- **Endpoints**:
  - `/api/admin/config/price-update` `GET`/`PUT` — read/update default `price_update_frequency_minutes` and `price_update_volatility` in `system_config`. [AdminConfigController](/backend/admin-service/src/main/kotlin/com/mbd/admin/controller/AdminConfigController.kt)
  - `/api/admin/monitoring/system-health`, `/api/admin/monitoring/active-users` — stub monitoring endpoints. [MonitoringController](/backend/admin-service/src/main/kotlin/com/mbd/admin/controller/MonitoringController.kt)

### 2.7 Backend dependency graph

```
user-service  ◄──── account-service  ◄──── portfolio-service
                                          ▲
                                          │ Feign
                                      fund-service
                                          ▲
                                          │ Kafka (fund-price-updates)
                                       portfolio-service
                                          │
admin-service  (independent; reads/writes system_config; admin role only)
```

- `account-service` → `user-service` (Feign, mTLS)
- `portfolio-service` → `account-service` and `fund-service` (Feign, mTLS)
- `fund-service` → Kafka → `portfolio-service` (async price updates)
- `admin-service` has no Feign clients; it only manages `system_config` and exposes monitoring. It is enforced as admin-only at both the Istio `AuthorizationPolicy` layer and Spring Security.

### 2.8 Database schema (shared `mbd` database)

| Table           | Owner service      | Key columns / FKs                                   |
|-----------------|--------------------|------------------------------------------------------|
| `users`         | user-service       | `keycloak_id`, `email` (unique)                      |
| `accounts`      | account-service    | `user_id` → `users(id)`                              |
| `transactions`  | account-service    | `account_id` → `accounts(id)`                        |
| `funds`         | fund-service       | `isin` (unique), `current_price`, `volatility`       |
| `holdings`      | portfolio-service  | `account_id` → `accounts`, `fund_id` → `funds`, unique(account_id, fund_id) |
| `system_config` | admin-service      | `key` (unique), `value`                              |

Each service runs **Flyway** with `baseline-on-migrate: true`, `baseline-version: 0`, and a **service-specific history table** so multiple services can migrate the same DB without clobbering each other's history.

---

## 3. Frontends

Two independent React 18 + Vite + TypeScript SPAs, styled with TailwindCSS, authenticated via `keycloak-js` + `@react-keycloak/web`. Both are served by nginx in-container and rely on Istio for routing and API proxying.

### 3.1 customer-frontend (`https://customer.mbd.local`)

- **Keycloak client**: `customer-frontend` (public client, PKCE S256, `check-sso`).
- **Pages**: `Dashboard`, `Register`, `Funds`.
- **API client**: `customerApi` calls `/api/users/*`, `/api/accounts/*`, `/api/funds`, `/api/portfolio/*` (same-origin; Istio routes `/api/*` to the right backend). [customerApi](/frontend/customer-frontend/src/services/customerApi.ts)
- **Axios interceptor** attaches the Keycloak bearer token to every request. [api](/frontend/customer-frontend/src/services/api.ts)
- **Routes** are wrapped in `ProtectedRoute` (requires authenticated session). [App](/frontend/customer-frontend/src/App.tsx)

### 3.2 admin-frontend (`https://admin.mbd.local`)

- **Keycloak client**: `admin-frontend` (public client).
- **Pages**: `Config` (price-update frequency/volatility), `AdminFunds` (CRUD funds + per-fund config).
- **API client**: `adminApi` calls `/api/admin/config/*`, `/api/admin/monitoring/*`, and `/api/funds/*`. [adminApi](/frontend/admin-frontend/src/services/adminApi.ts)
- **`ProtectedRoute`** accepts a `requiredRole="admin"` and checks `keycloak.hasRealmRole('admin')`, redirecting to `/unauthorized` otherwise. [ProtectedRoute](/frontend/admin-frontend/src/components/ProtectedRoute.tsx)

### 3.3 SSO flow

1. User visits `customer.mbd.local` or `admin.mbd.local`.
2. `ReactKeycloakProvider` (init `check-sso`) redirects to `https://keycloak.mbd.local/realms/mbd` if not authenticated.
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

Located in `infrastructure/k8s/istio/`:

- **`gateway.yaml`** — `mbd-gateway` on port 80 (redirect to HTTPS) and 443 (SIMPLE TLS using `mbd-tls-secret`), for hosts `customer.mbd.local`, `admin.mbd.local`, `keycloak.mbd.local`.
- **`peer-authentication.yaml`** — STRICT mTLS in both `mbd` and `mbd-infra`; PERMISSIVE for the `keycloak` pod (so JWKS can be fetched over plaintext inside the mesh).
- **`request-authentication.yaml`** — validates Keycloak JWTs for all pods in `mbd`; `forwardOriginalToken: true` so backends still receive the bearer.
- **`authorization-policy.yaml`** — four policies:
  - `api-access-policy`: allows service-to-service traffic from `cluster.local/ns/mbd/*` to `/api/*`, and external JWT-bearing requests to user/account/fund/portfolio paths.
  - `admin-service-policy`: requires a valid JWT principal for `/api/admin/*` on the `admin-service` pod.
  - `customer-frontend-public` / `admin-frontend-public`: allow `/*` to the nginx frontends (no JWT needed for static assets).
- **`destination-rules.yaml`** — `ISTIO_MUTUAL` TLS for `*.mbd.svc.cluster.local`, `*.mbd-infra.svc.cluster.local`, and cross-namespace `postgresql.mbd-infra.svc.cluster.local`.
- **VirtualServices** (`*-vs.yaml`) — route by host + URI prefix to each backend (with CORS allowing the two frontend origins) and to the frontends (catch-all on their respective hosts). The frontend VirtualServices are prefixed `z-` so they sort after the API VirtualServices and don't shadow `/api/*`.

### 4.4 PostgreSQL

`infrastructure/k8s/postgresql/` — a single-replica `StatefulSet` running `postgres:15-alpine` in `mbd-infra`, backed by a PVC, exposed via `postgresql.mbd-infra.svc.cluster.local:5432`. Credentials come from the `postgresql-secret` (created by `infrastructure/scripts/create-dev-secrets.sh`: user `mbdadmin`, db `mbd`, password `mbdpassword`).

### 4.5 Kafka (KRaft)

`infrastructure/k8s/kafka/` — single-broker `apache/kafka:3.7.0` in KRaft mode (combined broker+controller, no Zookeeper). Config in `configmap.yaml` advertises `kafka.mbd-infra.svc.cluster.local:9092`. `create-topics-job.yaml` provisions:

- `fund-price-updates` (3 partitions, RF 1) — produced by `fund-service`, consumed by `portfolio-service`.
- `portfolio-updates` (3 partitions, RF 1) — reserved for downstream/portfolio notifications (currently unused; `PortfolioService.publishPortfolioUpdates` is a stub).

### 4.6 Keycloak

`infrastructure/k8s/keycloak/` — `quay.io/keycloak/keycloak:23.0` in `mbd-infra`, backed by its own `keycloak-postgresql` StatefulSet, exposed via `keycloak.mbd-infra.svc.cluster.local:8080` and externally at `https://keycloak.mbd.local` through `mbd-gateway` + a `VirtualService`. `KC_PROXY=edge` lets Istio terminate TLS.

`configure-realm.sh` bootstraps the `mbd` realm:

- Realm roles: `customer`, `admin`.
- Clients: `mbd-backend` (confidential, service accounts), `customer-frontend` and `admin-frontend` (public, PKCE, redirect URIs on their respective hostnames).
- A `roles` client scope with an `oidc-usermodel-realm-role-mapper` so realm roles appear as a top-level `roles` claim in access tokens — this is what both Istio's `RequestAuthentication` and `admin-service`'s `JwtGrantedAuthoritiesConverter` (claim name `roles`, prefix `ROLE_`) rely on.

### 4.7 cert-manager

`infrastructure/k8s/cert-manager/` — a self-signed `mbd-ca` CA and a `mbd-ca-issuer` `ClusterIssuer`, which issues `mbd-tls-cert` (secret `mbd-tls-secret` in `istio-system`) for `customer.mbd.local`, `admin.mbd.local`, `keycloak.mbd.local`. The gateway references that secret for HTTPS.

### 4.8 ArgoCD (GitOps)

`infrastructure/argocd/` contains one `Application` per component, all pointing at `git@github.com:edwinbulter/mbd.git` `main`, with `automated.prune + selfHeal`:

- `root-app.yaml` (in `infrastructure/`) is the **app-of-apps**: it points ArgoCD at `infrastructure/argocd/` (non-recursive), which in turn contains all the per-component `Application` manifests.
- `project.yaml` defines the `mbd` `AppProject` scoped to the `mbd` and `mbd-infra` destinations.
- Per-component apps: `namespaces-app`, `istio-app`, `cert-manager-app`, `postgresql-app`, `kafka-app`, `keycloak-app`, and one per backend service / frontend (`user-service-app`, `account-service-app`, `fund-service-app`, `portfolio-service-app`, `admin-service-app`, `customer-frontend-app`, `admin-frontend-app`).

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

Add the Istio ingress gateway external IP to `/etc/hosts` for `customer.mbd.local`, `admin.mbd.local`, `keycloak.mbd.local`. Then:

- Customer frontend: `https://customer.mbd.local`
- Admin frontend: `https://admin.mbd.local`
- Keycloak admin console: `https://keycloak.mbd.local/admin`

Fallback port-forwards (bypassing the gateway) are documented in `doc/operation-notes.md` (ArgoCD `8081`, Kafka `9092`, Keycloak `8082`).

### 5.5 Making a user an admin

1. Register a user through the customer frontend.
2. In the Keycloak admin console, assign the `admin` **realm role** to that user.
3. Log out and back in to the admin frontend so the new role is in the access token.

---

## 6. End-to-End Request Flow Example

**Customer buys a fund** (`POST /api/portfolio/trade` from `customer-frontend`):

1. `customer-frontend` calls `customerApi.buyFund` → axios attaches the Keycloak bearer token → `POST https://customer.mbd.local/api/portfolio/trade`.
2. Istio `mbd-gateway` terminates TLS, `portfolio-service-vs` matches `/api/portfolio` prefix and routes to `portfolio-service:8080` (mTLS via `DestinationRule`).
3. `RequestAuthentication` validates the JWT against Keycloak JWKS; `api-access-policy` allows the request (valid principal).
4. `PortfolioController.executeTrade` → `PortfolioService.executeTrade`:
   - `FundClient.getFund(fundId)` over Feign/mTLS to `fund-service` → returns current price.
   - `AccountClient.getAccount(accountId)` over Feign/mTLS to `account-service` → checks balance.
   - `AccountClient.updateBalance(accountId, DepositDto(-totalCost))` debits the account; `account-service` records a `BUY_WITHDRAWAL` transaction.
   - Upserts the `Holding` (weighted-average price) and returns `HoldingDto`.
5. Later, `PriceUpdateScheduler` in `fund-service` ticks, updates `funds.current_price`, and publishes `FundPriceUpdate` to Kafka.
6. `FundPriceConsumer` in `portfolio-service` consumes it and updates `holdings.current_value` for every affected holding. The next `GET /api/portfolio/{accountId}` reflects the new total value.

**Admin changes price-update config** (`PUT /api/admin/config/price-update`):

1. `admin-frontend` → `https://admin.mbd.local/api/admin/config/price-update` with an admin-role JWT.
2. Gateway → `admin-service-vs` → `admin-service:8080`.
3. Istio `admin-service-policy` requires a valid JWT principal; Spring Security `SecurityConfig` requires `ROLE_admin` (from the `roles` claim).
4. `AdminConfigController.updatePriceUpdateConfig` upserts `system_config` rows for `price_update_frequency_minutes` and `price_update_volatility`.

---

## 7. Current State and Caveats

- This is an **MVP/learning project**. Notable simplifications:
  - `user-service` parses the JWT inline rather than using Spring Security OAuth2 resource server (only `admin-service` does).
  - `PriceUpdateScheduler` uses a hardcoded `@Scheduled(fixedRate = 300000)`; the admin-configured values in `system_config` are not yet wired into the scheduler (they're applied per-fund via `PUT /api/funds/{id}/config`).
  - `PortfolioService.publishPortfolioUpdates` and the `portfolio-updates` Kafka topic are stubs.
  - `MonitoringController.getActiveUsers` returns an empty list.
  - SELL trades are not supported (`executeTrade` only handles `BUY`).
  - Dev secrets are plaintext in `create-dev-secrets.sh`; not production-grade.
- The shared database with per-service Flyway history tables is a deliberate simplicity trade-off (per `plan-1.md`).
- Cross-namespace DB access (`mbd` → `mbd-infra` PostgreSQL) requires both the Istio `DestinationRule` (`postgresql-cross-rule`) and the `allow-app-to-postgres` `AuthorizationPolicy`.

---

## 8. Where to Look Next

- Original requirements and decisions: [plan-1](/doc/plan-1.md)
- Backend implementation guide: [backend-services-implementation](/doc/backend-services-implementation.md)
- Frontend implementation guide: [frontend-implementation](/doc/frontend-implementation.md)
- Build/run operations: [operation-notes](/doc/operation-notes.md)
- Backend testing: [backend-testing](/backend-testing.md)
- Infrastructure step-by-step: `doc/infrastructure/00-bootstrap-cluster.md` … `07-cert-manager-setup.md`.
