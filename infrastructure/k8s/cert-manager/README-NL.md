# cert-manager PKI voor MBD

De officiële online documentatie van cert-manager is te vinden op [https://cert-manager.io/docs/](https://cert-manager.io/docs/).

## Wat is een certificaat?
Een digitaal certificaat (specifiek een **X.509 certificaat**) is een elektronisch bestand dat de identiteit van een server (zoals een website) bewijst en wordt gebruikt voor het beveiligen van verbindingen via encryptie (HTTPS). Het fungeert als een digitaal paspoort en bevat onder andere:
- De **Public Key** van de server.
- De **identiteit** van de eigenaar (zoals DNS-namen: `customer.mbd.local`, etc.).
- De **digitale handtekening** van de instantie die het certificaat heeft uitgegeven (de Issuer).
- De **geldigheidsduur** (begin- en einddatum).

## Hoe werkt cert-manager?
cert-manager is een Kubernetes "controller". Het houdt continu de `Certificate`-resources in de cluster in de gaten. Het proces werkt als volgt:
1.  **Aanvraag:** Je maakt een `Certificate` resource aan waarin je beschrijft voor welke domeinen je een certificaat wilt en welke **Issuer** (uitgever) je wilt gebruiken.
2.  **Generatie:** cert-manager genereert een nieuw sleutelpaar (Public en Private Key).
3.  **Ondertekening:** De Public Key wordt naar de **Issuer** gestuurd om ondertekend te worden.
4.  **Opslag:** Zodra het certificaat terugkomt, slaat cert-manager het certificaat samen met de Private Key op in een Kubernetes **Secret**.
5.  **Monitoring:** cert-manager controleert dagelijks of het certificaat nog geldig is. Als het bijna verloopt, herhaalt het proces zich automatisch (vernieuwing).

### Wat is een Issuer?
Een **Issuer** (of **ClusterIssuer**) is de "ondertekenaar" of Certificate Authority (CA) van het certificaat. Het is de entiteit die cert-manager vertelt *hoe* en *door wie* een certificaat ondertekend moet worden.
-   **Publieke Issuers:** Bijvoorbeeld *Let's Encrypt*. Deze worden gebruikt voor echte websites op het internet.
-   **Interne/Self-signed Issuers:** Worden gebruikt voor lokale ontwikkeling of interne netwerken.

**In deze demo:** Omdat we op een lokale omgeving werken (`.local` domeinen), maken we gebruik van een **self-signed CA-structuur**. We hebben onze eigen kleine certificaatautoriteit binnen de cluster gemaakt die onze certificaten ondertekent. Dit is waarom je browser in eerste instantie een waarschuwing geeft (hij kent onze eigen gemaakte "uitgever" nog niet).

### Opslag van de Private Key
De private key is het meest gevoelige onderdeel van de PKI. cert-manager slaat deze op in een Kubernetes Secret (gedefinieerd door `secretName` in de `Certificate` resource).
-   **Locatie:** De private key wordt als een base64-gecodeerde string opgeslagen in het veld `tls.key` van het Secret.
-   **Beveiliging:** De toegang tot deze sleutel wordt beheerd via Kubernetes **RBAC** (Role-Based Access Control). Alleen pods of services die expliciet toegang hebben tot dat specifieke Secret (zoals de Istio Ingress Gateway) kunnen de private key lezen.
-   **Ontkoppeling:** De applicatie (of gateway) leest de sleutel uit het Secret zonder dat de applicatie zelf hoeft te weten hoe de sleutel is gegenereerd of vernieuwd.

Deze map bevat de cert-manager-resources die fungeren als de Public Key Infrastructure (PKI) voor het MBD-project. cert-manager beheert en vernieuwt het TLS-certificaat dat door de Istio ingress-gateway wordt gebruikt om HTTPS te serveren voor alle `*.mbd.local`-hostnamen.

Deze manifests worden uitgerold door de `mbd-cert-manager-config` ArgoCD-applicatie (`infrastructure/argocd/cert-manager-app.yaml`).

---

## Bestanden in deze map

| Bestand | Doel |
|---------|------|
| `cluster-issuer.yaml` | Definieert de self-signed root-issuer, het `mbd-ca`-CA-certificaat en de `mbd-ca-issuer` die leaf-certificaten ondertekent. |
| `mbd-tls-certificate.yaml` | Het leaf-`Certificate` voor de Istio-gateway, geldig voor alle `*.mbd.local`-DNS-namen. |

---

## PKI-hiërarchie

cert-manager wordt hier gebruikt als een private certificate authority in een gelaagde structuur:

```
selfsigned-issuer (ClusterIssuer, self-signed root)
        │
        ▼
   mbd-ca (Certificate, isCA: true)        ← de MBD-root-CA, opgeslagen in mbd-ca-secret
        │
        ▼
  mbd-ca-issuer (ClusterIssuer, CA type)   ← ondertekent leaf-certs met mbd-ca-secret
        │
        ▼
  mbd-tls-cert (Certificate, istio-system) ← leaf-cert voor *.mbd.local, opgeslagen in mbd-tls-secret
```

### 1. Self-signed root-issuer

`cluster-issuer.yaml` begint met een self-signed `ClusterIssuer`:

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: selfsigned-issuer
spec:
  selfSigned: {}
```

Dit is de bootstrap-issuer — hij ondertekent certificaten met een tijdelijke self-signed sleutel. Hij bestaat alleen om de MBD-root-CA aan te maken.

### 2. MBD-root-CA

Het `mbd-ca`-`Certificate` is een CA-certificaat (`isCA: true`) ondertekend door `selfsigned-issuer`:

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: mbd-ca
  namespace: cert-manager
spec:
  isCA: true
  commonName: mbd-ca
  secretName: mbd-ca-secret
  issuerRef:
    name: selfsigned-issuer
    kind: ClusterIssuer
    group: cert-manager.io
```

Het resulterende CA-certificaat en de bijbehorende private sleutel worden opgeslagen in het `mbd-ca-secret` Kubernetes-secret in de `cert-manager`-namespace. Dit is de trust root voor het hele project.

### 3. CA-issuer

De `mbd-ca-issuer` `ClusterIssuer` gebruikt het CA-secret om leaf-certificaten te ondertekenen:

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: mbd-ca-issuer
spec:
  ca:
    secretName: mbd-ca-secret
```

Elk `Certificate` dat verwijst naar `mbd-ca-issuer` wordt ondertekend door de MBD-root-CA.

### 4. Leaf TLS-certificaat

`mbd-tls-certificate.yaml` vraagt een leaf-certificaat aan voor de Istio-gateway:

```yaml
apiVersion: cert-manager.io/v1
kind: Certificate
metadata:
  name: mbd-tls-cert
  namespace: istio-system
spec:
  secretName: mbd-tls-secret
  issuerRef:
    name: mbd-ca-issuer
    kind: ClusterIssuer
    group: cert-manager.io
  dnsNames:
    - customer.mbd.local
    - admin.mbd.local
    - keycloak.mbd.local
    - kafbat.mbd.local
```

cert-manager schrijft het uitgegeven certificaat (en de private sleutel) naar het `mbd-tls-secret` Kubernetes-secret in de `istio-system`-namespace. De Istio `mbd-gateway` verwijst naar dit secret via het `credentialName`-veld (zie `infrastructure/k8s/istio/gateway.yaml`), waardoor de gateway TLS serveert voor alle vier de hostnamen.

cert-manager vernieuwt het certificaat automatisch voordat het verloopt — er is geen handmatige actie nodig.

---

## Hoe alles samenhangt

```
Browser ──HTTPS──► Istio Gateway (mbd-gateway)
                       │
                       │ credentialName: mbd-tls-secret
                       ▼
                 mbd-tls-secret (in istio-system)
                       │ uitgegeven door
                       ▼
                 mbd-ca-issuer (ClusterIssuer)
                       │ backed by
                       ▼
                 mbd-ca-secret (in cert-manager)
                       │ aangemaakt vanuit
                       ▼
                 mbd-ca Certificate (isCA: true)
                       │ ondertekend door
                       ▼
                 selfsigned-issuer (ClusterIssuer)
```

1. cert-manager bootstrap-t een self-signed CA (`mbd-ca`) via `selfsigned-issuer`.
2. `mbd-ca-issuer` gebruikt die CA om leaf-certificaten te ondertekenen.
3. Het `mbd-tls-cert`-`Certificate` vraagt een leaf-cert aan voor de vier `*.mbd.local`-DNS-namen.
4. cert-manager slaat het leaf-cert + sleutel op in `mbd-tls-secret` in `istio-system`.
5. De Istio ingress-gateway leest `mbd-tls-secret` en terminatet TLS voor al het inkomende HTTPS-verkeer.

---

## De CA vertrouwen in je browser

Omdat de MBD-CA self-signed is (geen publiek vertrouwde CA zoals Let's Encrypt), toont je browser een certificaatwaarschuwing bij het bezoeken van `https://customer.mbd.local` enz. Om dit tijdens lokale ontwikkeling te voorkomen, importeer je het `mbd-ca`-root-certificaat in de trust store van je browser/systeem:

```bash
# Exporteer het CA-certificaat uit de cluster
kubectl get secret mbd-ca-secret -n cert-manager -o jsonpath='{.data.ca\.crt}' | base64 -d > mbd-ca.crt

# macOS: voeg het toe aan de system keychain en markeer het als vertrouwd
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain mbd-ca.crt
```

Daarna worden alle `*.mbd.local`-certificaten die door `mbd-ca-issuer` zijn ondertekend vertrouwd door je browser.

---

## Een nieuwe hostnaam toevoegen

Om een nieuwe `*.mbd.local`-host via de gateway te serveren:

1. Voeg de DNS-naam toe aan `mbd-tls-certificate.yaml` onder `dnsNames`.
2. Voeg dezelfde hostnaam toe aan `infrastructure/k8s/istio/gateway.yaml` (zowel bij de HTTP- als de HTTPS-server-hostlijsten).
3. Maak of update een `VirtualService` die de nieuwe host naar een service routeert.
4. Voeg de hostnaam toe aan `/etc/hosts`.
5. Commit en push — ArgoCD sync't, en cert-manager geeft `mbd-tls-secret` automatisch opnieuw uit met de nieuwe DNS-naam erin.

---

## Vereisten

Deze manifests gaan ervan uit dat cert-manager al in de cluster is geïnstalleerd (cert-manager wordt **niet** door deze map geïnstalleerd). Zie `doc/infrastructure/07-cert-manager-setup.md` voor de installatiestappen. De cert-manager-controller moet draaien in de `cert-manager`-namespace voordat de `ClusterIssuer`- en `Certificate`-resources worden gereconciled.
