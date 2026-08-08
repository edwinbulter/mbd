# Cert-Manager Setup

This guide explains how to install and configure cert-manager for the MBD project.

## Prerequisites

- Kind cluster running
- Istio installed
- ArgoCD installed

## Steps

### 1. Install cert-manager

We install cert-manager using the official manifest.

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.3/cert-manager.yaml
```

Wait for all pods to be ready in the `cert-manager` namespace.

### 2. Configure Issuers and Certificates

Our configuration is managed via GitOps and located in `infrastructure/k8s/cert-manager/`.

1. **selfsigned-issuer**: A root ClusterIssuer that issues the CA certificate.
2. **mbd-ca**: A Certificate that creates the CA secret `mbd-ca-secret`.
3. **mbd-ca-issuer**: A ClusterIssuer that uses the CA secret to issue end-entity certificates.
4. **mbd-tls-cert**: The Certificate for our frontends, creating `mbd-tls-secret` in the `mbd` namespace.

### 3. Verify TLS Secret

Once the manifests are applied, verify the secret exists:

```bash
kubectl get secret mbd-tls-secret -n mbd
```

### 4. Local Trust (Optional)

Since we use a self-signed CA, your browser will show a warning. To avoid this, you can export the CA certificate and add it to your trusted root store:

```bash
kubectl get secret mbd-ca-secret -n mbd-infra -o jsonpath='{.data.ca\.crt}' | base64 -d > mbd-ca.crt
```
