# Frontend Implementation Plan (Phase 3, TLS-secured)

This document describes the steps to implement the **customer-frontend** and **admin-frontend** for the MBD (My Bank Demo) application, as specified in `plan-1.md` Phase 3.

Both frontends are single-page applications (SPAs) built with **React**, bundled with **Vite**, and styled with **TailwindCSS**. They authenticate users via **Keycloak SSO** and communicate with backend services through the **Istio Gateway**.

---

## 1. Project Structure

The frontend workspace is located under `frontend/` in the monorepo:

```
frontend/
├── customer-frontend/
│   ├── src/
│   │   ├── components/    # Reusable UI components
│   │   ├── pages/         # Dashboard, Portfolio, etc.
│   │   ├── services/      # Axios API clients
│   │   ├── types/         # TypeScript interfaces
│   │   ├── utils/         # Keycloak config
│   │   ├── App.tsx        # Routing and Auth Provider
│   │   └── main.tsx
│   ├── Dockerfile
│   ├── nginx.conf
│   └── (config files)
├── admin-frontend/
│   └── (same structure)
```

---

## 2. Infrastructure & Routing

### 2.1 Hostnames

We use separate hostnames for each component to avoid routing conflicts:

- **Customer Frontend**: `https://customer.mbd.local`
- **Admin Frontend**: `https://admin.mbd.local`
- **Keycloak**: `https://keycloak.mbd.local`

**Action**: Add these to your local `/etc/hosts` pointing to the Kind cluster Ingress IP.

### 2.2 TLS with cert-manager

We use **cert-manager** to automatically issue and renew TLS certificates.
- **ClusterIssuer**: `mbd-ca-issuer` (self-signed CA)
- **Certificate**: `mbd-tls-cert` generates the `mbd-tls-secret` in the `mbd` namespace.
- **Gateway**: `mbd-gateway` is configured to use `mbd-tls-secret` for port 443.

### 2.3 Istio Security

- **RequestAuthentication**: Validates Keycloak JWT tokens for all backend services in the `mbd` namespace.
- **AuthorizationPolicy**: 
  - `api-access-policy`: Requires a valid token for all `/api/*` endpoints.
  - `admin-service-policy`: Strictly restricts `/api/admin/*` to users with the `admin` role and the `admin-frontend` client.

---

## 3. Keycloak Configuration

### 3.1 Clients and Roles

- **Realm**: `mbd`
- **Roles**: `customer`, `admin`
- **Public Clients**:
  - `customer-frontend`: Redirects to `https://customer.mbd.local/*`
  - `admin-frontend`: Redirects to `https://admin.mbd.local/*`
- **Protocol Mapper**: A realm-role mapper ensures that roles are included in the top-level `roles` claim of the JWT.

---

## 4. Development and Deployment

### 4.1 Local Development

1. Install dependencies: `npm install` in each frontend directory.
2. Start dev server: `npm run dev`.
3. Proxy: Vite is configured to handle the same-origin routing if needed, but in the cluster, Istio handles everything.

### 4.2 Docker Build

```bash
docker build -t customer-frontend:latest frontend/customer-frontend
docker build -t admin-frontend:latest frontend/admin-frontend
```

### 4.3 GitOps with ArgoCD

Frontends are deployed via ArgoCD using the manifests in:
- `infrastructure/argocd/customer-frontend-app.yaml`
- `infrastructure/argocd/admin-frontend-app.yaml`

The manifests are located in `infrastructure/k8s/<frontend-name>/`.
