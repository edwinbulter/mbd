# Istio Setup

This guide explains how to install and configure Istio for the MBD project.

## Prerequisites

- Kind cluster running
- mbd and mbd-infra namespaces created (from 01-namespace-setup.md)
- kubectl configured to use the Kind cluster
- Cluster admin permissions

## Steps

### 1. Check if Istio is Already Installed

```bash
# Check if Istio is installed in the cluster
kubectl get pods -n istio-system

# If istio-system namespace exists, Istio is already installed
kubectl get namespace istio-system
```

### 2. Install Istio (if not already installed)

#### Download Istio

```bash
# Download Istio 1.20.x (or latest stable)
curl -L https://istio.io/downloadIstio | sh -

# Navigate to the Istio directory
cd istio-1.20.x

# Add istioctl to your PATH
export PATH=$PWD/bin:$PATH
```

#### Install Istio

```bash
# Install Istio with default profile
istioctl install --set profile=default -y

# Controleer of de pods (istiod en ingress) op 'Running' staan
k get pods -n istio-system

# Controleer of de services zijn aangemaakt
k get svc -n istio-system
```

### 3. Configure Istio for MBD Namespaces

The namespaces should already be labeled from the namespace setup step (via the namespaces.yaml manifest). Verify:

```bash
kubectl get namespace mbd -L istio-injection
kubectl get namespace mbd-infra -L istio-injection
```

**Important:** The Istio labels are managed through the `infrastructure/k8s/namespaces.yaml` manifest. Do not use kubectl label commands as this will cause drift with ArgoCD.

### 4. Enable mTLS for Service-to-Service Communication

The PeerAuthentication manifest file `infrastructure/k8s/istio/peer-authentication.yaml` has already been created with the following content:

```yaml
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: mbd
spec:
  mtls:
    mode: STRICT
---
apiVersion: security.istio.io/v1beta1
kind: PeerAuthentication
metadata:
  name: default
  namespace: mbd-infra
spec:
  mtls:
    mode: STRICT
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the PeerAuthentication:

```bash
kubectl apply -f infrastructure/k8s/istio/peer-authentication.yaml
```

### 5. Create Istio Ingress Gateway

The Gateway manifest file `infrastructure/k8s/istio/gateway.yaml` has already been created with the following content:

```yaml
# infrastructure/k8s/istio/gateway.yaml
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
        - "*"
    - port:
        number: 443
        name: https
        protocol: HTTPS
      tls:
        mode: SIMPLE
        credentialName: mbd-tls-secret
      hosts:
        - "*"
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the Gateway:

```bash
kubectl apply -f infrastructure/k8s/istio/gateway.yaml
```

### 6. Create Destination Rules for mTLS

The DestinationRules manifest file `infrastructure/k8s/istio/destination-rules.yaml` has already been created with the following content:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: default
  namespace: mbd
spec:
  host: "*.mbd.svc.cluster.local"
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL
---
apiVersion: networking.istio.io/v1beta1
kind: DestinationRule
metadata:
  name: default
  namespace: mbd-infra
spec:
  host: "*.mbd-infra.svc.cluster.local"
  trafficPolicy:
    tls:
      mode: ISTIO_MUTUAL
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the DestinationRules:

```bash
kubectl apply -f infrastructure/k8s/istio/destination-rules.yaml
```

### 7. Configure API Routing with VirtualServices

VirtualServices define routing rules for traffic entering through the Gateway. Each backend service will have its own VirtualService to route requests to the correct service.

**Note:** VirtualServices for backend services will be created when the services are deployed. This section documents the pattern and structure.

#### VirtualService Pattern

For each backend service, create a VirtualService in the `infrastructure/k8s/istio/` directory. Example structure:

```yaml
# infrastructure/k8s/istio/user-service-vs.yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: user-service
  namespace: mbd
spec:
  hosts:
    - "*"
  gateways:
    - mbd/mbd-gateway
  http:
    - match:
        - uri:
            prefix: /api/users
      route:
        - destination:
            host: user-service
            port:
              number: 8080
```

#### JWT Validation at Gateway Level

To validate JWT tokens at the gateway level, add a RequestAuthentication policy:

```yaml
# infrastructure/k8s/istio/jwt-authn.yaml
apiVersion: security.istio.io/v1beta1
kind: RequestAuthentication
metadata:
  name: jwt-authn
  namespace: mbd
spec:
  selector:
    matchLabels:
      app: user-service
  jwtRules:
    - issuer: "http://keycloak.mbd-infra.svc.cluster.local:8080/realms/mbd"
      jwksUri: "http://keycloak.mbd-infra.svc.cluster.local:8080/realms/mbd/protocol/openid-connect/certs"
```

#### CORS Configuration

For frontend access, configure CORS policies:

```yaml
# infrastructure/k8s/istio/cors-policy.yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: cors-policy
  namespace: mbd
spec:
  hosts:
    - "*"
  gateways:
    - mbd/mbd-gateway
  http:
    - match:
        - uri:
            prefix: /api
      corsPolicy:
        allowOrigins:
          - exact: http://localhost:3000
          - exact: http://localhost:3001
        allowMethods:
          - POST
          - GET
          - OPTIONS
          - PUT
          - DELETE
        allowHeaders:
          - "*"
        exposeHeaders:
          - "*"
        maxAge: 24h
      route:
        - destination:
            host: user-service
            port:
              number: 8080
```

**Important:** These VirtualService files will be created when backend services are deployed. They must be committed to your GitHub repository for ArgoCD management.

### 8. Commit Istio Manifests to GitHub

Add all Istio manifest files to your GitHub manifests repository:

```bash
# Add files to git
git add infrastructure/k8s/istio/peer-authentication.yaml
git add infrastructure/k8s/istio/gateway.yaml
git add infrastructure/k8s/istio/destination-rules.yaml

# Commit and push
git commit -m "Add Istio manifests for MBD project"
git push origin main
```

### 9. Next Steps

After completing this guide, proceed to:

1. **03-postgresql-setup.md** - Deploy PostgreSQL
2. **04-kafka-setup.md** - Deploy Kafka
3. **05-keycloak-setup.md** - Deploy Keycloak
4. **06-argocd-setup.md** - Install ArgoCD and configure GitOps

**Note:** ArgoCD application manifests will be applied in step 6 (06-argocd-setup.md) after all infrastructure is deployed. VirtualServices for backend services will be created when services are deployed.

### 10. Verify Istio Installation

```bash
# Check Istio pods
kubectl get pods -n istio-system

# Check Istio services
kubectl get svc -n istio-system

# Check Istio components
istioctl dashboard

# Verify mTLS is enabled
kubectl get peerauthentication -n mbd
kubectl get peerauthentication -n mbd-infra
```

### 10. Test Istio Injection

Deploy a test pod to verify automatic sidecar injection:

```bash
# Deploy a test pod
kubectl run test-pod --image=nginx -n mbd --restart=Never

# Check if sidecar is injected
kubectl get pod test-pod -n mbd -o jsonpath='{.spec.containers[*].name}'

# You should see: istio-init istio-proxy nginx

# Clean up test pod
kubectl delete pod test-pod -n mbd
```

## Cleanup

To remove Istio resources from MBD namespaces (without uninstalling Istio cluster-wide):

```bash
# Delete via ArgoCD (recommended)
argocd app delete mbd-istio

# Or delete directly (not recommended - ArgoCD will recreate)
kubectl delete peerauthentication -n mbd --all
kubectl delete destinationrule -n mbd --all
kubectl delete gateway -n mbd --all

# Remove Istio resources from mbd-infra namespace
kubectl delete peerauthentication -n mbd-infra --all
kubectl delete destinationrule -n mbd-infra --all

# Remove Istio injection labels (update namespaces.yaml instead)
kubectl label namespace mbd istio-injection-
kubectl label namespace mbd-infra istio-injection-
```

**Important:** If you delete resources directly, ArgoCD will recreate them on the next sync. To permanently remove, delete the ArgoCD application or remove the manifests from Git.

To completely uninstall Istio from the cluster:

```bash
istioctl uninstall --purge -y
```

## Verification

Run these commands to verify the setup:

```bash
# Check Istio is running
kubectl get pods -n istio-system

# Check namespaces are labeled
kubectl get namespace mbd -L istio-injection
kubectl get namespace mbd-infra -L istio-injection

# Check mTLS is enforced
kubectl get peerauthentication -n mbd
kubectl get peerauthentication -n mbd-infra

# Check gateway exists
kubectl get gateway -n mbd

# Check destination rules
kubectl get destinationrule -n mbd
kubectl get destinationrule -n mbd-infra

# Check ArgoCD application status
argocd app get mbd-istio
```

## ArgoCD Integration

Once the Istio manifests are in GitHub and the ArgoCD application is configured:

1. ArgoCD will continuously monitor the Git repository
2. Any changes to the Istio manifests in Git will be automatically synced to the cluster
3. Manual changes via kubectl will be detected as drift and either reverted or flagged
4. All Istio resources are version-controlled and auditable

## Troubleshooting

### Sidecar Not Injected

If pods don't get the Istio sidecar:

```bash
# Check namespace label
kubectl get namespace mbd -L istio-injection

# Check webhook
kubectl get mutatingwebhookconfiguration -n istio-system

# Check pod events
kubectl describe pod <pod-name> -n mbd
```

### mTLS Connection Issues

If services can't communicate due to mTLS:

```bash
# Check PeerAuthentication
kubectl get peerauthentication -n mbd

# Check DestinationRules
kubectl get destinationrule -n mbd

# Check service mesh status
istioctl proxy-status
```

### Gateway Not Accessible

If the gateway is not accessible:

```bash
# Check gateway status
kubectl get gateway -n mbd

# Check ingress gateway service
kubectl get svc istio-ingressgateway -n istio-system

# Check gateway logs
kubectl logs -n istio-system -l app=istio-ingressgateway
```
