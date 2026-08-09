# cert-manager PKI for MBD

This folder contains the cert-manager resources that act as the Public Key Infrastructure (PKI) for the MBD project. cert-manager issues and renews the TLS certificate used by the Istio ingress gateway to serve HTTPS for all `*.mbd.local` hostnames.

These manifests are deployed by the `mbd-cert-manager-config` ArgoCD application (`infrastructure/argocd/cert-manager-app.yaml`).

---

## Files in this folder

| File | Purpose |
|------|---------|
| `cluster-issuer.yaml` | Defines the self-signed root issuer, the `mbd-ca` CA certificate, and the `mbd-ca-issuer` that signs leaf certificates. |
| `mbd-tls-certificate.yaml` | The leaf `Certificate` for the Istio gateway, covering all `*.mbd.local` DNS names. |

---

## PKI hierarchy

cert-manager is used here as a private certificate authority in a three-tier structure:

```
selfsigned-issuer (ClusterIssuer, self-signed root)
        │
        ▼
   mbd-ca (Certificate, isCA: true)        ← the MBD root CA, stored in mbd-ca-secret
        │
        ▼
  mbd-ca-issuer (ClusterIssuer, CA type)   ← signs leaf certs using mbd-ca-secret
        │
        ▼
  mbd-tls-cert (Certificate, istio-system) ← leaf cert for *.mbd.local, stored in mbd-tls-secret
```

### 1. Self-signed root issuer

`cluster-issuer.yaml` starts with a self-signed `ClusterIssuer`:

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: selfsigned-issuer
spec:
  selfSigned: {}
```

This is the bootstrap issuer — it signs certificates using a throwaway self-signed key. It exists only to create the MBD root CA.

### 2. MBD root CA

The `mbd-ca` `Certificate` is a CA certificate (`isCA: true`) signed by `selfsigned-issuer`:

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

The resulting CA certificate and private key are stored in the `mbd-ca-secret` Kubernetes secret in the `cert-manager` namespace. This is the trust root for the entire project.

### 3. CA issuer

The `mbd-ca-issuer` `ClusterIssuer` uses the CA secret to sign leaf certificates:

```yaml
apiVersion: cert-manager.io/v1
kind: ClusterIssuer
metadata:
  name: mbd-ca-issuer
spec:
  ca:
    secretName: mbd-ca-secret
```

Any `Certificate` that references `mbd-ca-issuer` will be signed by the MBD root CA.

### 4. Leaf TLS certificate

`mbd-tls-certificate.yaml` requests a leaf certificate for the Istio gateway:

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

cert-manager writes the issued certificate (and its private key) to the `mbd-tls-secret` Kubernetes secret in the `istio-system` namespace. The Istio `mbd-gateway` references this secret by name in its `credentialName` field (see `infrastructure/k8s/istio/gateway.yaml`), which is how the gateway serves TLS for all four hostnames.

cert-manager automatically renews the certificate before it expires — no manual intervention is needed.

---

## How it all fits together

```
Browser ──HTTPS──► Istio Gateway (mbd-gateway)
                       │
                       │ credentialName: mbd-tls-secret
                       ▼
                 mbd-tls-secret (in istio-system)
                       │ issued by
                       ▼
                 mbd-ca-issuer (ClusterIssuer)
                       │ backed by
                       ▼
                 mbd-ca-secret (in cert-manager)
                       │ created from
                       ▼
                 mbd-ca Certificate (isCA: true)
                       │ signed by
                       ▼
                 selfsigned-issuer (ClusterIssuer)
```

1. cert-manager bootstraps a self-signed CA (`mbd-ca`) via `selfsigned-issuer`.
2. `mbd-ca-issuer` uses that CA to sign leaf certificates.
3. The `mbd-tls-cert` `Certificate` requests a leaf cert for the four `*.mbd.local` DNS names.
4. cert-manager stores the leaf cert + key in `mbd-tls-secret` in `istio-system`.
5. The Istio ingress gateway reads `mbd-tls-secret` and terminates TLS for all incoming HTTPS traffic.

---

## Trusting the CA in your browser

Because the MBD CA is self-signed (not a publicly trusted CA like Let's Encrypt), your browser will show a certificate warning when visiting `https://customer.mbd.local` etc. To suppress this during local development, import the `mbd-ca` root certificate into your browser/system trust store:

```bash
# Export the CA certificate from the cluster
kubectl get secret mbd-ca-secret -n cert-manager -o jsonpath='{.data.ca\.crt}' | base64 -d > mbd-ca.crt

# macOS: add it to the system keychain and mark it as trusted
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain mbd-ca.crt
```

After this, all `*.mbd.local` certificates signed by `mbd-ca-issuer` will be trusted by your browser.

---

## Adding a new hostname

To serve a new `*.mbd.local` host through the gateway:

1. Add the DNS name to `mbd-tls-certificate.yaml` under `dnsNames`.
2. Add the same hostname to `infrastructure/k8s/istio/gateway.yaml` (both the HTTP and HTTPS server host lists).
3. Create or update a `VirtualService` that routes the new host to a service.
4. Add the hostname to `/etc/hosts`.
5. Commit and push — ArgoCD will sync, and cert-manager will automatically reissue `mbd-tls-secret` with the new DNS name included.

---

## Prerequisites

These manifests assume cert-manager is already installed in the cluster (it is **not** installed by this folder). See `doc/infrastructure/07-cert-manager-setup.md` for the install steps. The cert-manager controller must be running in the `cert-manager` namespace for the `ClusterIssuer` and `Certificate` resources to be reconciled.
