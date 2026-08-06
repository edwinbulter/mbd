Use case:

Om ervaring op te doen met een specifieke techstack gaan we de software opzetten voor een concurrent van BND (Brandnewday) met de naam MBD (My Bank Demo). 

Nieuwe gebruikers moeten zich kunnen registreren, een beleggings rekening kunnen openen, fondsen kunnen instellen voor de rekening en geld kunnen inleggen. Vervolgens moet het mogelijk zijn voor de gebruikers om te zien wat er op hun rekening staat en het bedrag op hun rekening moet constant worden aangepast op basis van de koersen van de fondsen. 

Er moet een proces draaien dat de waarden van de fondsen regelmatig (random) aanpast.

Er moeten 2 verschillende beheerschermen zijn voor bankmedewerkers en via SSO moet er voor worden gezorgd dat ze maar 1 keer hoeven in te loggen. 

 

Wat het moet bevatten:

Kafka stream toepassing, 

PostgreSQL DB, 

Azure APIM Self-Hosted Gateway

Spring Boot microservices geschreven in Kotlin als backend die onderling via een service-mesh mTLS communiceren

2 frontends die via via keycloak SSO aanloggen en vervolgens communiceren met de Azure APIM via TLS

runnen in lokaal K8 kind cluster

ArgoCD gebruiken voor deployen van wijzigingen in K8 manifests in de remote Git Repo

---

I need to clarify several important aspects before creating the implementation plan:

Scope & Prioritization

Should we build a complete system from day 1, or start with an MVP (core user flows first, admin screens later)?
  MVP: core user flow first
What are the minimum features needed for the first working version?
  User must be able to register, open an investment account, set up funds, deposit money, and view their portfolio with updated values.


Technology Choices

Frontend: Which framework? (React, Vue, Angular, Svelte?)
  Use React with vite
Service Mesh: Istio, Linkerd, or another?
  Istio
Keycloak: Local deployment or do you have a cloud instance?
  Local Deployment in K8 cluster
Repository structure: Monorepo (all services in one repo) or multi-repo?
  Monorepo

Infrastructure Details

Azure APIM Self-Hosted Gateway: Do you have an Azure subscription/instance, or should we simulate this locally?
  I have an Azure subscription but haven't used APIM locally
PostgreSQL: Local instance or cloud-hosted?
  Local hosted in my K8 Kind Cluster
Kafka: Local (docker/k8s) or cloud-based?
  Local hosted in my K8 Kind Cluster

Microservices Architecture

How many microservices do you envision? (e.g., user-service, account-service, fund-service, portfolio-service?)
  You have to help me with thinking about the functionality for this application and how to split it up in microservices.
Should each service have its own database schema, or shared database?
  For simplicity use a shared database

Data & Business Logic

Fund data: Should we use real market data APIs or generate mock data?
  generate mock data
Random price updates: What frequency and volatility parameters?
  this would be something like every 5 minutes with a volatility of 2% but I would like to be able to set these from the employer management interface.
Account types: Only investment accounts, or also savings/checking?
  Only investment accounts

Authentication & Authorization

User roles: Regular users, bank employees (any other roles?)
  No
SSO flow: Keycloak as identity provider, with Azure APIM as the API gateway?
  Yes

GitOps & Deployment

Remote Git Repo: GitHub, GitLab, or another provider?
  GitHub
ArgoCD: Should it manage all resources (including infrastructure) or just application deployments?
  Just application deployments

Please provide guidance on these points so I can create a realistic, actionable implementation plan.

---

# MBD Banking Application Implementation Plan

This plan outlines the implementation of a complete banking application (MBD - My Bank Demo) with microservices architecture, local Kubernetes deployment, and GitOps workflow.

## Architecture Overview

**Microservices (5 services):**
- user-service - User registration, profile management, Keycloak integration
- account-service - Investment account creation, deposits, account management
- fund-service - Fund CRUD operations, publishes price updates to Kafka
- portfolio-service - Calculates portfolio values, tracks holdings, consumes Kafka price updates
- admin-service - Admin screens for bank employees, configuration (price update frequency/volatility)

**Frontends (2 React + Vite apps):**
- customer-frontend - Regular user interface
- admin-frontend - Bank employee interface

**Infrastructure:**
- Existing Kind Kubernetes cluster (use dedicated namespaces)
- Istio service mesh (mTLS, namespace-scoped)
- Keycloak (local K8s deployment in dedicated namespace)
- PostgreSQL (local K8s in dedicated namespace)
- Kafka KRaft mode (local K8s in dedicated namespace, no Zookeeper)
- Azure APIM Self-Hosted Gateway
- ArgoCD for GitOps (namespace-scoped)

## Phase 1: Infrastructure Setup

### 1.1 Documentation Creation
Create markdown instruction files for infrastructure setup in `doc/infrastructure/`:
- `01-namespace-setup.md` - Namespace creation and configuration
- `02-istio-setup.md` - Istio installation and mTLS configuration
- `03-postgresql-setup.md` - PostgreSQL deployment and configuration
- `04-kafka-setup.md` - Kafka KRaft mode deployment
- `05-keycloak-setup.md` - Keycloak deployment and realm configuration
- `06-argocd-setup.md` - ArgoCD installation and GitHub integration
- `07-azure-apim-setup.md` - Azure APIM Self-Hosted Gateway configuration

### 1.2 Namespace Configuration
- Create dedicated namespace: `mbd`
- Create namespace for infrastructure: `mbd-infra`
- Configure resource quotas per namespace
- Set up network policies

### 1.3 Service Mesh Installation
- Install Istio in existing Kind cluster (if not already installed)
- Configure Istio for mbd namespaces only
- Configure mTLS for service-to-service communication
- Set up Istio ingress gateway in mbd namespace

### 1.4 Infrastructure Services
- Deploy PostgreSQL in mbd-infra namespace with persistent storage
- Deploy Kafka (KRaft mode) in mbd-infra namespace
- Deploy Keycloak in mbd-infra namespace with realm configuration
- Configure Keycloak SSO with roles (user, employee)

### 1.5 Azure APIM Setup
- Set up Azure APIM instance
- Configure Self-Hosted Gateway
- Define API policies for backend services
- Configure Keycloak OAuth2 integration

## Phase 2: Backend Services Implementation

### 2.1 Project Structure (Monorepo)
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

### 2.2 Shared Components
- Common DTOs and models
- Database configuration (shared PostgreSQL)
- Kafka configuration
- Security utilities (JWT parsing, role checks)
- Istio mTLS configuration

### 2.3 User Service
- Spring Boot + Kotlin
- REST API: register, login, get profile
- Keycloak integration for authentication
- Database tables: users
- Endpoints secured via Keycloak

### 2.4 Account Service
- Spring Boot + Kotlin
- REST API: create account, deposit money, get account details
- Database tables: accounts, transactions
- Integration with user-service for validation
- Endpoints exposed via Azure APIM

### 2.5 Fund Service
- Spring Boot + Kotlin
- REST API: create fund, list funds, get fund details
- Database tables: funds
- Kafka producer for price updates
- Scheduled job for random price generation (configurable frequency/volatility)
- Admin API to configure price update parameters

### 2.6 Portfolio Service
- Spring Boot + Kotlin
- REST API: get portfolio, view holdings
- Database tables: holdings, portfolio_snapshots
- Kafka consumer for price updates
- Real-time portfolio value calculation
- Webhook/notification for portfolio updates

### 2.7 Admin Service
- Spring Boot + Kotlin
- REST API: configure price update frequency, volatility
- Database tables: system_config
- Employee-only access (Keycloak role)
- System monitoring endpoints

## Phase 3: Frontend Implementation

### 3.1 Customer Frontend (React + Vite)
- User registration/login (Keycloak SSO)
- Dashboard with portfolio overview
- Account creation flow
- Fund selection interface
- Deposit money interface
- Real-time portfolio value display (polling/SSE)
- TailwindCSS for styling

### 3.2 Admin Frontend (React + Vite)
- Employee login (Keycloak SSO)
- System configuration panel (price update frequency, volatility)
- User management view
- Fund management interface
- System monitoring dashboard
- TailwindCSS for styling

### 3.3 SSO Integration
- Keycloak JavaScript adapter
- OAuth2 flow configuration
- Token management
- Role-based UI rendering

## Phase 4: Kafka Streaming

### 4.1 Kafka Topics
- fund-price-updates - Fund price changes
- portfolio-updates - Portfolio value recalculations

### 4.2 Price Update Process
- Fund service scheduled job generates random price changes
- Publishes to fund-price-updates topic
- Portfolio service consumes updates
- Recalculates portfolio values
- Publishes to portfolio-updates topic
- Frontend polls portfolio service for updates

### 4.3 Configuration
- Default: 5-minute intervals, 2% volatility
- Configurable via admin interface
- Stored in system_config table

## Phase 5: Kubernetes Manifests

### 5.1 Service Manifests
- Deployment configs for each service
- Service resources (ClusterIP)
- ConfigMaps and Secrets
- Resource limits and requests

### 5.2 Istio Configuration
- VirtualServices for routing
- DestinationRules for mTLS
- Gateway configuration
- AuthorizationPolicies

### 5.3 Infrastructure Manifests
- PostgreSQL StatefulSet
- Kafka (KRaft mode) StatefulSet
- Keycloak Deployment
- Persistent volume claims

### 5.4 Azure APIM Gateway
- Self-Hosted Gateway deployment
- Configuration via ConfigMap
- TLS certificates

## Phase 6: ArgoCD GitOps Setup

### 6.1 Repository Structure
- GitHub repository for manifests
- Application definitions
- Environment-specific configs

### 6.2 ArgoCD Installation
- Deploy ArgoCD to Kind cluster
- Configure GitHub repository
- Set up sync policies

### 6.3 Application Definitions
- Separate ArgoCD apps for infrastructure and services
- Auto-sync on git push
- Health checks for deployments

## Phase 7: Security & Networking

### 7.1 mTLS Configuration
- Istio mutual TLS between services
- Certificate management
- Service-to-service authentication

### 7.2 SSO Flow
- Frontend → Keycloak (OAuth2)
- Frontend → Azure APIM (JWT in Authorization header)
- Azure APIM → Backend services (forward JWT)
- Backend services validate JWT via Keycloak

### 7.3 Role-Based Access
- Regular users: customer-frontend access only
- Bank employees: admin-frontend + customer-frontend access
- Service-level role validation

## Phase 8: Testing & Validation

### 8.1 Integration Testing
- User registration flow
- Account creation and deposit
- Fund price updates via Kafka
- Portfolio value calculation
- SSO login/logout

### 8.2 End-to-End Testing
- Complete user journey
- Admin configuration changes
- Real-time portfolio updates

## Implementation Order

1. Infrastructure setup (Kind, Istio, PostgreSQL, Kafka, Keycloak)
2. Shared backend components
3. User service + Keycloak integration
4. Account service
5. Fund service + Kafka producer
6. Portfolio service + Kafka consumer
7. Admin service
8. Customer frontend
9. Admin frontend
10. Azure APIM configuration
11. Kubernetes manifests
12. ArgoCD setup
13. End-to-end testing

## Key Considerations

- Shared PostgreSQL for simplicity (single schema)
- Monorepo structure for easier development
- Local-first approach (all infrastructure local)
- Mock data for funds (no external APIs)
- Configurable price update parameters
- Role-based access control via Keycloak
- GitOps for deployment automation

## Phase 9: Cleanup & Reinstallation

### 9.1 Cleanup Scripts
Create scripts to clean up the project without affecting the Kind cluster:

**`./scripts/cleanup.sh`**
- Delete mbd and mbd-infra namespaces
- Clean up ArgoCD resources from namespaces
- Remove Istio resources from namespaces (not cluster-wide)
- Delete local build artifacts
- Clear Kafka data volumes
- Clear PostgreSQL data volumes
- **DO NOT delete Kind cluster**

**`./scripts/cleanup-db.sh`**
- Drop all database tables
- Reset PostgreSQL data
- Clear Kafka topics
- Reset Keycloak realm data

### 9.2 Reinstallation Scripts
Create scripts for quick reinstallation:

**`./scripts/install-infrastructure.sh`**
- Verify existing Kind cluster is running
- Create mbd and mbd-infra namespaces
- Install/configure Istio for namespaces (if not cluster-wide)
- Deploy PostgreSQL in mbd-infra namespace
- Deploy Kafka (KRaft mode) in mbd-infra namespace
- Deploy Keycloak in mbd-infra namespace
- Configure Keycloak realm and roles
- Deploy ArgoCD in mbd namespace

**`./scripts/install-apps.sh`**
- Build and push Docker images
- Apply Kubernetes manifests to mbd namespace
- Configure ArgoCD applications
- Verify all services are running

**`./scripts/reset-all.sh`**
- Combines cleanup + full reinstallation
- Single command to reset entire environment
- **DO NOT touch Kind cluster**

### 9.3 Development Workflow
- Use cleanup scripts between major changes
- Use reset-all for complete environment refresh
- Database-only reset for data changes
- Infrastructure-only reset for K8s/Istio changes

### 9.4 Script Features
- Confirmation prompts before destructive operations
- Dry-run mode to preview changes
- Logging of all operations
- Error handling and rollback
- Health checks after installation
