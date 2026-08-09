# Istio-configuratie voor MBD

Deze map bevat alle Istio-resources voor het MBD-project. Istio wordt hier in twee rollen gebruikt:

1. **API Gateway** — een enkel TLS-terminerend ingress-punt dat extern browserverkeer op basis van hostnaam en URL-pad naar de juiste frontend- of backendservice routeert, en Keycloak-JWT's aan de edge valideert.
2. **Service mesh** — automatische sidecar-injectie voor alle pods in de gelabelde namespaces, met STRICT mTLS die elke service-to-service-aanroep binnen het cluster versleutelt.

Deze manifests worden uitgerold door de `mbd-istio` ArgoCD-applicatie (`infrastructure/argocd/istio-app.yaml`) in de `mbd`-namespace, maar een aantal resources is ook namespace-scoped naar `mbd-infra`.

---

## Bestanden in deze map

| Bestand | Doel |
|---------|------|
| `gateway.yaml` | De `mbd-gateway` ingress-Gateway (poort 80 → 443 redirect, 443 TLS). |
| `*-vs.yaml` | VirtualServices die host + pad naar een specifieke service routeren. |
| `peer-authentication.yaml` | Handhaaft STRICT mTLS in `mbd` en `mbd-infra` (met een PERMISSIVE-uitzondering voor Keycloak JWKS). |
| `destination-rules.yaml` | Vertelt clients om `ISTIO_MUTUAL` TLS te gebruiken bij aanroepen van services in `mbd`, `mbd-infra` en de cross-namespace PostgreSQL. |
| `request-authentication.yaml` | Valideert Keycloak-JWT's voor alle pods in `mbd`. |
| `authorization-policy.yaml` | ALLOW-policies die bepalen wie `/api/*` en `/api/admin/*` mag aanroepen, en de frontends publiek toegankelijk maken. |

---

## 1. Sidecar-injectie

Istio-sidecars worden **per namespace** ingeschakeld via het `istio-injection: enabled`-label. Dit staat in `infrastructure/k8s/namespaces.yaml`:

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

Dankzij dit label injecteert de mutating admission webhook van Istio een Envoy-proxy-sidecar in elke pod die in `mbd` of `mbd-infra` wordt aangemaakt. De sidecar onderschept al het inkomende en uitgaande verkeer van de pod, waardoor mTLS, JWT-validatie en authorization-policies werken zonder applicatiecode aan te passen.

Een aantal pods gebruikt daarnaast de `holdApplicationUntilProxyStarts: true`-annotatie (zie bijv. `infrastructure/k8s/user-service/deployment.yaml`) zodat de applicatiecontainer wacht tot de Envoy-sidecar ready is voordat hij start — dit voorkomt startup-fouten wanneer de app een andere service probeert aan te roepen voordat de mesh draait. Resourcelimits voor de sidecar worden afgesteld via `sidecar.istio.io/proxyCPU` / `proxyMemory`-annotaties op de kleinere workloads.

---

## 2. Service mesh — mTLS

### 2.1 PeerAuthentication (serverzijde)

`peer-authentication.yaml` handhaaft mTLS voor **inkomend** verkeer naar pods:

```yaml
# mbd-namespace — STRICT
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: mbd
spec:
  mtls:
    mode: STRICT
---
# mbd-infra-namespace — STRICT
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: mbd-infra
spec:
  mtls:
    mode: STRICT
---
# Keycloak-uitzondering — PERMISSIVE
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

`STRICT` betekent dat een pod alleen verbindingen accepteert die via Envoy mTLS-geauthenticeerd zijn. De `keycloak-jwks`-policy overschrijft dit specifiek voor de Keycloak-pod en zet deze op `PERMISSIVE`, zodat de eigen `RequestAuthentication` van Istio JWKS over plaintext binnen de mesh kan ophalen (de JWKS-URI in `request-authentication.yaml` gebruikt `http://keycloak.mbd-infra.svc.cluster.local:8080/...`).

### 2.2 DestinationRules (clientzijde)

`destination-rules.yaml` vertelt Envoy om `ISTIO_MUTUAL` te gebruiken voor **uitgaand** verkeer:

```yaml
# Alle services in de mbd-namespace
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
# Cross-namespace: mbd-apps die PostgreSQL in mbd-infra aanroepen
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
# Alle services in de mbd-infra-namespace
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

`ISTIO_MUTUAL` betekent dat Envoy de interne CA van Istio gebruikt om certificaten automatisch uit te geven en te roteren — geen handmatig certificaatbeheer nodig.

### 2.3 Wat dit in de praktijk betekent

- `account-service` die `user-service` aanroept → mTLS, zonder app-code.
- `portfolio-service` die `account-service` en `fund-service` aanroept → mTLS.
- `portfolio-service` die consumeert van `kafka.mbd-infra.svc.cluster.local:9092` → mTLS.
- Elke `mbd`-backend die `postgresql.mbd-infra.svc.cluster.local:5432` aanroept → mTLS (daarom bestaat `allow-app-to-postgres.yaml` in `infrastructure/k8s/` — deze staat de cross-namespace-aanroep toe op het Istio-authorization-niveau).

---

## 3. API Gateway

### 3.1 De Gateway-resource

`gateway.yaml` definieert een enkele ingress-gateway, `mbd-gateway`, die de Istio ingress-gateway-deployment selecteert (`istio: ingressgateway`) en vier hostnamen serveert:

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
        httpsRedirect: true          # ← alle HTTP wordt naar HTTPS geredirect
    - port:
        number: 443
        name: https
        protocol: HTTPS
      tls:
        mode: SIMPLE                 # ← gateway terminatet TLS
        credentialName: mbd-tls-secret   # ← uitgegeven door cert-manager
      hosts:
        - "customer.mbd.local"
        - "admin.mbd.local"
        - "keycloak.mbd.local"
        - "kafbat.mbd.local"
```

Het TLS-certificaat (`mbd-tls-secret`) wordt door cert-manager geleverd (zie `infrastructure/k8s/cert-manager/`) en dekt alle vier de `*.mbd.local`-hostnamen. De Kind-config mapt host-poorten 80 en 443 naar de ingress-gateway, zodat je er via de `*.mbd.local`-entries in `/etc/hosts` bij komt.

### 3.2 VirtualServices — host + pad-routing

Elke VirtualService koppelt aan `mbd/mbd-gateway` en routeert op basis van hostnaam en URL-prefix:

| VirtualService | Host(s) | Pad-prefix | Routeert naar |
|----------------|---------|------------|---------------|
| `user-service-vs.yaml` | `customer.mbd.local`, `admin.mbd.local` | `/api/users` | `user-service:8080` |
| `account-service-vs.yaml` | `customer.mbd.local`, `admin.mbd.local` | `/api/accounts` | `account-service:8080` |
| `fund-service-vs.yaml` | `customer.mbd.local`, `admin.mbd.local` | `/api/funds` | `fund-service:8080` |
| `portfolio-service-vs.yaml` | `customer.mbd.local`, `admin.mbd.local` | `/api/portfolio` | `portfolio-service:8080` |
| `admin-service-vs.yaml` | `admin.mbd.local` | `/api/admin` | `admin-service:8080` |
| `customer-frontend-vs.yaml` | `customer.mbd.local` | `/*` (catch-all) | `customer-frontend:80` |
| `admin-frontend-vs.yaml` | `admin.mbd.local` | `/*` (catch-all) | `admin-frontend:80` |
| `keycloak/virtualservice.yaml` | `keycloak.mbd.local` | `/` | `keycloak:8080` (in `mbd-infra`) |
| `kafbat-ui/virtualservice.yaml` | `kafbat.mbd.local` | `/` | `kafbat-ui.mbd-infra.svc.cluster.local:8080` |

De backend-VirtualServices stellen ook een CORS-policy in die `https://customer.mbd.local` en `https://admin.mbd.local` als origins toestaat, met de standaard HTTP-methoden en een 24h max-age.

> **Naamgevingsopmerking:** de frontend-VirtualServices hebben een `z-`-prefix (`z-customer-frontend`, `z-admin-frontend`) zodat Istio ze **na** de `/api/*`-VirtualServices evalueert. Zonder dit zou een catch-all op `customer.mbd.local` de API-routes overschaduwen.

### 3.3 RequestAuthentication — JWT-validatie aan de edge

`request-authentication.yaml` laat Envoy Keycloak-JWT's valideren voor elk verzoek naar een pod in de `mbd`-namespace:

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

`forwardOriginalToken: true` betekent dat de originele `Authorization: Bearer ...`-header nog steeds wordt doorgestuurd naar de backend, zodat `admin-service` zijn eigen Spring Security-rolcontrole op hetzelfde token kan uitvoeren.

### 3.4 AuthorizationPolicy — wie wat mag aanroepen

`authorization-policy.yaml` bevat vier ALLOW-policies:

1. **`api-access-policy`** (namespace `mbd`, geen selector → geldt voor alle pods in `mbd`):
   - Staat service-to-service-verkeer van `cluster.local/ns/mbd/*` naar `/api/*` toe (geen JWT nodig voor interne aanroepen).
   - Staat externe verzoeken met een geldig JWT-principal toe naar `/api/users`, `/api/accounts`, `/api/funds`, `/api/portfolio` en hun sub-paden.

2. **`admin-service-policy`** (selector `app: admin-service`):
   - Vereist een geldig JWT-principal voor `/api/admin/*`. De daadwerkelijke `admin`-rolcontrole wordt daarna opnieuw in `admin-service` afgedwongen door Spring Security (`hasRole('admin')`).

3. **`customer-frontend-public`** (selector `app: customer-frontend`):
   - Staat `/*` toe zonder JWT — de statische assets van de SPA moeten publiek bereikbaar zijn.

4. **`admin-frontend-public`** (selector `app: admin-frontend`):
   - Hetzelfde als hierboven voor de statische assets van de admin-frontend.

Er is ook nog een aparte `AuthorizationPolicy` in `infrastructure/k8s/allow-app-to-postgres.yaml` (in de `mbd-infra`-namespace) die pods uit namespace `mbd` toestaat de `postgresql`-pod te bereiken — dit is vereist omdat de cross-namespace-DB-verbinding via mTLS is versleuteld via de `postgresql-cross-rule` DestinationRule.

---

## 4. End-to-end verzoekflow-voorbeeld

Een klant die `GET https://customer.mbd.local/api/portfolio/1` aanroept:

1. Browser resolveert `customer.mbd.local` (via `/etc/hosts`) naar het Kind-ingress-IP.
2. `mbd-gateway` terminatet TLS met `mbd-tls-secret`.
3. `portfolio-service-vs` matcht host `customer.mbd.local` + prefix `/api/portfolio` → routeert naar `portfolio-service:8080`.
4. Envoy valideert de `Authorization`-JWT tegen Keycloak-JWKS (`RequestAuthentication`).
5. `api-access-policy` staat het verzoek toe (geldig principal op `/api/portfolio/*`).
6. De sidecar stuurt het verzoek door naar `portfolio-service` over mTLS.
7. `portfolio-service` roept `account-service` en `fund-service` aan via Feign — deze uitgaande aanroepen zijn ook mTLS (`mbd-internal-and-cross` DestinationRule).
8. De reactie stroomt terug via de sidecar en de gateway naar de browser.

---

## 5. Een nieuwe service aan de mesh toevoegen

Om een nieuwe backend via de gateway te routeren:

1. Maak een `VirtualService` in deze map (kopieer `fund-service-vs.yaml` als sjabloon), stel de host-prefix en destination in.
2. Als de service JWT-beschermd moet zijn, is geen extra werk nodig — `api-access-policy` dekt `/api/*` al voor geldige principals. Voeg het nieuwe pad toe aan de `paths`-lijst van de policy als het buiten `/api/users|accounts|funds|portfolio` valt.
3. Als de service admin-only-toegang nodig heeft, voeg dan een selector-gebaseerde `AuthorizationPolicy` toe zoals `admin-service-policy`.
4. mTLS is automatisch — de `mbd-internal-and-cross` DestinationRule dekt al `*.mbd.svc.cluster.local`.
5. Commit en push; ArgoCD sync't de `mbd-istio`-applicatie.

---

## 6. Vereisten

Deze manifests gaan ervan uit dat het volgende al in de cluster is geïnstalleerd (niet beheerd door deze map):

- Istio control plane + ingress-gateway in `istio-system`.
- cert-manager en de `mbd-ca-issuer` ClusterIssuer (zie `infrastructure/k8s/cert-manager/`).
- Het `mbd-tls-secret` TLS-secret in `istio-system` (uitgegeven door cert-manager vanuit `mbd-tls-certificate.yaml`).
- De `mbd`- en `mbd-infra`-namespaces met `istio-injection: enabled` (zie `infrastructure/k8s/namespaces.yaml`).

Zie `doc/infrastructure/02-istio-setup.md` en `07-cert-manager-setup.md` voor de installatiestappen.
