# Istio Configuration for MBD

Official online documentation for Istio can be found at [https://istio.io/latest/docs/](https://istio.io/latest/docs/).
-   **API Gateway:** [Istio Gateway documentation](https://istio.io/latest/docs/tasks/traffic-management/ingress/)
-   **Service Mesh:** [What is Istio?](https://istio.io/latest/docs/concepts/what-is-istio/)

This folder contains all Istio resources for the MBD project. Istio is used here in two roles:

1. **API Gateway** — a single TLS-terminating ingress point that routes external browser traffic by hostname and URL path to the right frontend or backend service, and validates Keycloak JWTs at the edge.
2. **Service mesh** — automatic sidecar injection for all pods in the labeled namespaces, with STRICT mTLS encrypting every service-to-service call inside the cluster.

These manifests are deployed by the `mbd-istio` ArgoCD application (`infrastructure/argocd/istio-app.yaml`) into the `mbd` namespace, but several resources are namespace-scoped to `mbd-infra` as well.

---

## Files in this folder

| File | Purpose |
|------|---------|
| `gateway.yaml` | The `mbd-gateway` ingress Gateway (ports 80 → 443 redirect, 443 TLS). |
| `*-vs.yaml` | VirtualServices that route host + path to a specific service. |
| `peer-authentication.yaml` | Enforces STRICT mTLS in `mbd` and `mbd-infra` (with a PERMISSIVE exception for Keycloak JWKS). |
| `destination-rules.yaml` | Tells clients to use `ISTIO_MUTUAL` TLS when calling services in `mbd`, `mbd-infra`, and the cross-namespace PostgreSQL. |
| `request-authentication.yaml` | Validates Keycloak JWTs for all pods in `mbd`. |
| `authorization-policy.yaml` | ALLOW policies that gate who can call `/api/*` and `/api/admin/*`, and make the frontends public. |

---

## 1. Sidecar injection

Istio sidecars are enabled **per namespace** via the `istio-injection: enabled` label. This is set in `infrastructure/k8s/namespaces.yaml`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: mbd
  labels:
    istio-injection: enabled
---
apiVersion: v1
kind: Namespace
metadata:
  name: mbd-infra
  labels:
    istio-injection: enabled
```

Because of this label, Istio's mutating admission webhook injects an Envoy proxy sidecar into every pod created in `mbd` and `mbd-infra`. The sidecar intercepts all inbound and outbound traffic to/from the pod, which is what makes mTLS, JWT validation, and authorization policies work without any application code changes.

A few pods also use the `holdApplicationUntilProxyStarts: true` annotation (see e.g. `infrastructure/k8s/user-service/deployment.yaml`) so the app container waits for the Envoy sidecar to be ready before starting — this avoids startup failures when the app tries to call another service before the mesh is up. Resource limits for the sidecar are tuned via `sidecar.istio.io/proxyCPU` / `proxyMemory` annotations on the smaller workloads.

---

## 2. Service mesh — mTLS

### 2.1 PeerAuthentication (server side)

`peer-authentication.yaml` enforces mTLS for **inbound** traffic to pods:

```yaml
# mbd namespace — STRICT
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: mbd
spec:
  mtls:
    mode: STRICT
---
# mbd-infra namespace — STRICT
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: mbd-infra
spec:
  mtls:
    mode: STRICT
---
# Keycloak exception — PERMISSIVE
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: keycloak-jwks
  namespace: mbd-infra
spec:
  selector:
    matchLabels:
      app: keycloak
  mtls:
    mode: PERMISSIVE
```

`STRICT` means a pod will only accept connections that are mTLS-authenticated by Envoy. The `keycloak-jwks` policy overrides this for the Keycloak pod specifically, setting it to `PERMISSIVE` so that Istio's own `RequestAuthentication` can fetch JWKS over plaintext inside the mesh (the JWKS URI in `request-authentication.yaml` uses `http://keycloak.mbd-infra.svc.cluster.local:8080/...`).

### 2.2 DestinationRules (client side)

`destination-rules.yaml` tells Envoy to use `ISTIO_MUTUAL` for **outbound** traffic:

```yaml
# All services in the mbd namespace
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: mbd-internal-and-cross
  namespace: mbd
spec:
  host: "*.mbd.svc.cluster.local"
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL
---
# Cross-namespace: mbd apps calling PostgreSQL in mbd-infra
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: postgresql-cross-rule
  namespace: mbd
spec:
  host: postgresql.mbd-infra.svc.cluster.local
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL
---
# All services in the mbd-infra namespace
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: mbd-infra-internal
  namespace: mbd-infra
spec:
  host: "*.mbd-infra.svc.cluster.local"
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL
```

`ISTIO_MUTUAL` means Envoy uses Istio's internal CA to issue and rotate certificates automatically — no manual cert management needed.

### 2.3 What this means in practice

- `account-service` calling `user-service` → mTLS, no app code involved.
- `portfolio-service` calling `account-service` and `fund-service` → mTLS.
- `portfolio-service` consuming from `kafka.mbd-infra.svc.cluster.local:9092` → mTLS.
- Any `mbd` backend calling `postgresql.mbd-infra.svc.cluster.local:5432` → mTLS (this is why `allow-app-to-postgres.yaml` in `infrastructure/k8s/` exists — it permits the cross-namespace call at the Istio authorization layer).

---

## 3. API Gateway

### 3.1 The Gateway resource

`gateway.yaml` defines a single ingress gateway, `mbd-gateway`, that selects the Istio ingress gateway deployment (`istio: ingressgateway`) and serves four hostnames:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: Gateway
metadata:
  name: mbd-gateway
  namespace: mbd
spec:
  selector:
    istio: ingressgateway
  servers:
    - port:
        number: 80
        name: http
        protocol: HTTP
      hosts:
        - "customer.mbd.local"
        - "admin.mbd.local"
        - "keycloak.mbd.local"
        - "kafbat.mbd.local"
      tls:
        httpsRedirect: true          # ← all HTTP is redirected to HTTPS
    - port:
        number: 443
        name: https
        protocol: HTTPS
      tls:
        mode: SIMPLE                 # ← gateway terminates TLS
        credentialName: mbd-tls-secret   # ← issued by cert-manager
      hosts:
        - "customer.mbd.local"
        - "admin.mbd.local"
        - "keycloak.mbd.local"
        - "kafbat.mbd.local"
```

The TLS certificate (`mbd-tls-secret`) is provisioned by cert-manager (see `infrastructure/k8s/cert-manager/`) and covers all four `*.mbd.local` hostnames. The Kind config maps host ports 80 and 443 to the ingress gateway, so you reach it via the `*.mbd.local` entries in `/etc/hosts`.

### 3.2 VirtualServices — host + path routing

Each VirtualService attaches to `mbd/mbd-gateway` and routes based on hostname and URL prefix:

| VirtualService | Host(s) | Path prefix | Routes to |
|----------------|---------|-------------|-----------|
| `user-service-vs.yaml` | `customer.mbd.local`, `admin.mbd.local` | `/api/users` | `user-service:8080` |
| `account-service-vs.yaml` | `customer.mbd.local`, `admin.mbd.local` | `/api/accounts` | `account-service:8080` |
| `fund-service-vs.yaml` | `customer.mbd.local`, `admin.mbd.local` | `/api/funds` | `fund-service:8080` |
| `portfolio-service-vs.yaml` | `customer.mbd.local`, `admin.mbd.local` | `/api/portfolio` | `portfolio-service:8080` |
| `admin-service-vs.yaml` | `admin.mbd.local` | `/api/admin` | `admin-service:8080` |
| `customer-frontend-vs.yaml` | `customer.mbd.local` | `/*` (catch-all) | `customer-frontend:80` |
| `admin-frontend-vs.yaml` | `admin.mbd.local` | `/*` (catch-all) | `admin-frontend:80` |
| `keycloak/virtualservice.yaml` | `keycloak.mbd.local` | `/` | `keycloak:8080` (in `mbd-infra`) |
| `kafbat-ui/virtualservice.yaml` | `kafbat.mbd.local` | `/` | `kafbat-ui.mbd-infra.svc.cluster.local:8080` |

The backend VirtualServices also set a CORS policy allowing `https://customer.mbd.local` and `https://admin.mbd.local` origins, with the standard HTTP methods and a 24h max-age.

> **Naming note:** the frontend VirtualServices are prefixed `z-` (`z-customer-frontend`, `z-admin-frontend`) so that Istio evaluates them **after** the `/api/*` VirtualServices. Without this, a catch-all on `customer.mbd.local` would shadow the API routes.

### 3.3 RequestAuthentication — JWT validation at the edge

`request-authentication.yaml` makes Envoy validate Keycloak-issued JWTs for every request to a pod in the `mbd` namespace:

```yaml
apiVersion: security.istio.io/v1beta1
kind: RequestAuthentication
metadata:
  name: jwt-authn
  namespace: mbd
spec:
  jwtRules:
    - issuer: "https://keycloak.mbd.local/realms/mbd"
      jwksUri: "http://keycloak.mbd-infra.svc.cluster.local:8080/realms/mbd/protocol/openid-connect/certs"
      forwardOriginalToken: true
```

`forwardOriginalToken: true` means the original `Authorization: Bearer ...` header is still passed through to the backend, so `admin-service` can do its own Spring Security role check on the same token.

### 3.4 AuthorizationPolicy — who can call what

`authorization-policy.yaml` contains four ALLOW policies:

1. **`api-access-policy`** (namespace `mbd`, no selector → applies to all pods in `mbd`):
   - Allows service-to-service traffic from `cluster.local/ns/mbd/*` to `/api/*` (no JWT needed for internal calls).
   - Allows external requests with a valid JWT principal to `/api/users`, `/api/accounts`, `/api/funds`, `/api/portfolio` and their sub-paths.

2. **`admin-service-policy`** (selector `app: admin-service`):
   - Requires a valid JWT principal for `/api/admin/*`. The actual `admin` role check is then enforced again inside `admin-service` by Spring Security (`hasRole('admin')`).

3. **`customer-frontend-public`** (selector `app: customer-frontend`):
   - Allows `/*` with no JWT — the SPA's static assets must be publicly reachable.

4. **`admin-frontend-public`** (selector `app: admin-frontend`):
   - Same as above for the admin frontend's static assets.

There is also a separate `AuthorizationPolicy` in `infrastructure/k8s/allow-app-to-postgres.yaml` (in the `mbd-infra` namespace) that permits pods from namespace `mbd` to reach the `postgresql` pod — this is required because the cross-namespace DB connection is mTLS-encrypted via the `postgresql-cross-rule` DestinationRule.

---

## 4. End-to-end request flow example

A customer calling `GET https://customer.mbd.local/api/portfolio/1`:

1. Browser resolves `customer.mbd.local` (via `/etc/hosts`) to the Kind ingress IP.
2. `mbd-gateway` terminates TLS using `mbd-tls-secret`.
3. `portfolio-service-vs` matches host `customer.mbd.local` + prefix `/api/portfolio` → routes to `portfolio-service:8080`.
4. Envoy validates the `Authorization` JWT against Keycloak JWKS (`RequestAuthentication`).
5. `api-access-policy` allows the request (valid principal on `/api/portfolio/*`).
6. The sidecar forwards the request to `portfolio-service` over mTLS.
7. `portfolio-service` calls `account-service` and `fund-service` via Feign — these outbound calls are also mTLS (`mbd-internal-and-cross` DestinationRule).
8. Response flows back through the sidecar and gateway to the browser.

---

## 5. Adding a new service to the mesh

To route a new backend through the gateway:

1. Create a `VirtualService` in this folder (copy `fund-service-vs.yaml` as a template), set the host prefix and destination.
2. If the service should be JWT-protected, no extra work is needed — `api-access-policy` already covers `/api/*` for valid principals. Add the new path to the policy's `paths` list if it lives outside `/api/users|accounts|funds|portfolio`.
3. If the service needs admin-only access, add a selector-based `AuthorizationPolicy` like `admin-service-policy`.
4. mTLS is automatic — the `mbd-internal-and-cross` DestinationRule already covers `*.mbd.svc.cluster.local`.
5. Commit and push; ArgoCD will sync the `mbd-istio` application.

---

## 6. Prerequisites

These manifests assume the following are already installed in the cluster (not managed by this folder):

- Istio control plane + ingress gateway in `istio-system`.
- cert-manager and the `mbd-ca-issuer` ClusterIssuer (see `infrastructure/k8s/cert-manager/`).
- The `mbd-tls-secret` TLS secret in `istio-system` (issued by cert-manager from `mbd-tls-certificate.yaml`).
- The `mbd` and `mbd-infra` namespaces with `istio-injection: enabled` (see `infrastructure/k8s/namespaces.yaml`).

See `doc/infrastructure/02-istio-setup.md` and `07-cert-manager-setup.md` for the install steps.
