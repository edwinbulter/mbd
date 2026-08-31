# Cluster Bootstrap Manual (Kind/GitOps)

This guide describes how to set up the MBD (My Bank Demo) environment on a **Kind (Kubernetes in Docker)** cluster using the **App-of-Apps** (Root App) pattern.

**IMPORTANT**: Follow these steps **in order**. Istio and cert-manager MUST be installed before ArgoCD deploys applications, otherwise the deployment will fail.

---

## 1. Prerequisites

Ensure you have the following tools installed:

- **Docker Desktop** or **OrbStack** (for running Kind)
- **Kind** (`brew install kind` on macOS)
- **kubectl** (`brew install kubectl`)
- **istioctl** (`brew install istioctl`)
- **argocd CLI** (`brew install argocd`)
- **GitHub SSH Key**: Configured in your GitHub account for repository access

---

## 2. Create Kind Cluster

Create a single-node Kind cluster with proper port mappings for HTTP/HTTPS traffic:

```bash
# Create the cluster
kind create cluster --name single-node --config - <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
  extraPortMappings:
  - containerPort: 80
    hostPort: 80
    protocol: TCP
  - containerPort: 443
    hostPort: 443
    protocol: TCP
EOF

# Verify the cluster is running
kubectl cluster-info --context kind-single-node
```

**Note**: The cluster name is `single-node` - you'll need this later when loading Docker images.

---

## 3. Install Core Infrastructure (CRITICAL - Do This BEFORE ArgoCD)

These components MUST be installed manually before ArgoCD deploys any applications. If you skip this step, ArgoCD sync will fail.

### 3.1. Install Istio

```bash
istioctl install --set profile=default -y

# Verify Istio is running
kubectl get pods -n istio-system
```

Expected output: `istiod` and `istio-ingressgateway` pods should be Running.

### 3.2. Configure Istio Ingress Gateway for Kind

Kind clusters require the ingress gateway to bind directly to the node's ports 80 and 443 using `hostPort`. This is different from cloud providers where LoadBalancer services get external IPs automatically.

```bash
# Patch the ingress gateway to use hostPort
kubectl patch deployment istio-ingressgateway -n istio-system --type='json' -p='[
  {
    "op": "add",
    "path": "/spec/template/spec/containers/0/ports/1/hostPort",
    "value": 80
  },
  {
    "op": "add",
    "path": "/spec/template/spec/containers/0/ports/2/hostPort",
    "value": 443
  }
]'

# Wait for the ingress gateway to restart with new configuration
kubectl rollout status deployment/istio-ingressgateway -n istio-system --timeout=120s

# Verify the pod is running
kubectl get pods -n istio-system -l app=istio-ingressgateway
```

This binds container port 8080 (HTTP) to host port 80 and container port 8443 (HTTPS) to host port 443, allowing Kind's port mappings to route traffic correctly.

### 3.3. Install cert-manager

```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.3/cert-manager.yaml

# Wait for cert-manager to be ready
kubectl wait --for=condition=ready pod -l app.kubernetes.io/instance=cert-manager -n cert-manager --timeout=120s
```

Expected output: 3 pods (cert-manager, cert-manager-cainjector, cert-manager-webhook) should be Running.

**Why this order matters**: ArgoCD applications include Istio VirtualServices and cert-manager Certificates. Without the CRDs installed first, ArgoCD cannot sync these resources.

---

## 4. Install ArgoCD

```bash
# Create ArgoCD namespace
kubectl create namespace argocd

# Install ArgoCD
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Wait for ArgoCD to be ready
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=argocd-server -n argocd --timeout=300s

# Get initial admin password
argocd admin initial-password -n argocd
```

### 4.1. Access ArgoCD UI (Optional)

```bash
# Port forward to access the UI
kubectl port-forward svc/argocd-server -n argocd 8081:443

# Open https://localhost:8081 in your browser
# Login with username: admin, password: (from command above)
```

---

## 5. Create Development Secrets

These secrets are required for PostgreSQL and Keycloak to start:

```bash
# Make script executable
chmod +x infrastructure/scripts/create-dev-secrets.sh

# Create secrets in both namespaces
./infrastructure/scripts/create-dev-secrets.sh
```

This creates:
- `postgres-secret` in `mbd-infra` namespace (PostgreSQL credentials)
- `keycloak-secret` in `mbd-infra` namespace (Keycloak admin & DB credentials)

---

## 6. Configure ArgoCD Git Repository Access

ArgoCD needs access to your Git repository to deploy applications.

### Option A: Using SSH Keys (Recommended)

```bash
# Generate SSH key for ArgoCD (if you don't have one)
ssh-keygen -t ed25519 -f ~/.ssh/argocd-ssh-key -N ""

# Display public key
cat ~/.ssh/argocd-ssh-key.pub
```

1. Copy the public key
2. Go to GitHub → Your repository → Settings → Deploy keys
3. Add the public key with **write access** (needed for status updates)

```bash
# Login to ArgoCD CLI
argocd login localhost:8081 --insecure

# Add repository to ArgoCD
argocd repo add git@github.com:edwinbulter/mbd.git \
  --ssh-private-key-path ~/.ssh/argocd-ssh-key

# Verify repository is connected
argocd repo list
```

### Option B: Using Personal Access Token

```bash
# Add repository with PAT
argocd repo add https://github.com/edwinbulter/mbd.git \
  --username your-github-username \
  --password your-github-pat

# Verify
argocd repo list
```

---

## 7. Build and Load Docker Images into Kind

**IMPORTANT**: Build and load images BEFORE deploying applications. Kind clusters cannot access Docker images built on your host unless you explicitly load them.

### 7.1. Build All Backend Services

```bash
cd backend

# Build all services (creates JAR files)
./gradlew build -x test

# Build Docker images (from each service directory)
cd user-service && docker build -t user-service:latest . && cd ..
cd account-service && docker build -t account-service:latest . && cd ..
cd fund-service && docker build -t fund-service:latest . && cd ..
cd portfolio-service && docker build -t portfolio-service:latest . && cd ..
cd admin-service && docker build -t admin-service:latest . && cd ..

cd ..
```

### 7.2. Build Frontend Images

```bash
# Customer frontend
cd frontend/customer-frontend
docker build -t customer-frontend:latest .

# Admin frontend
cd ../admin-frontend
docker build -t admin-frontend:latest .

cd ../..
```

### 7.3. Load Images into Kind Cluster

**This is the critical step** - without this, pods will fail with `ErrImageNeverPull`:

```bash
# Load all images at once
kind load docker-image \
  user-service:latest \
  account-service:latest \
  fund-service:latest \
  portfolio-service:latest \
  admin-service:latest \
  customer-frontend:latest \
  admin-frontend:latest \
  --name single-node
```

This process takes 1-2 minutes as it transfers ~2GB of images to the Kind node.

---

## 8. Deploy Root Application (GitOps Automation)

Now that images are loaded, deploy all infrastructure and application components:

```bash
# Apply the root application
kubectl apply -f infrastructure/root-app.yaml

# Watch applications being created
kubectl get applications -n argocd -w
```

This will create and sync all applications:
- Infrastructure: namespaces, istio config, postgresql, kafka, keycloak, cert-manager config
- Backend services: user-service, account-service, fund-service, portfolio-service, admin-service
- Frontends: customer-frontend, admin-frontend
- Monitoring: kafbat-ui

**Wait for all applications to sync**: This may take 2-3 minutes for ArgoCD to detect and sync all apps.

```bash
# Check status
kubectl get applications -n argocd -o custom-columns=NAME:.metadata.name,SYNC:.status.sync.status,HEALTH:.status.health.status
```

All applications should show `Synced` and `Healthy`.

---

## 9. Verify All Pods Are Running

```bash
# Check infrastructure pods
kubectl get pods -n mbd-infra

# Check application pods
kubectl get pods -n mbd

# All pods should show 1/1 READY and Running status
```

**Troubleshooting**:
- If pods show `ErrImageNeverPull`: Images not loaded into Kind (go back to step 7.3)
- If pods show `CrashLoopBackOff`: Check logs with `kubectl logs -n <namespace> <pod-name>`
- If Keycloak fails: Ensure `keycloak-postgresql-0` pod is running

---

## 10. Configure Local DNS

Find the Istio Ingress Gateway port mapping:

```bash
# For Kind, the ingress is exposed on localhost ports 80/443
# Check the service
kubectl get svc -n istio-system istio-ingressgateway
```

Update `/etc/hosts` to route `*.mbd.local` domains to localhost:

```bash
# Add these lines to /etc/hosts
127.0.0.1 customer.mbd.local
127.0.0.1 admin.mbd.local
127.0.0.1 keycloak.mbd.local
127.0.0.1 kafbat.mbd.local
```

**Verify DNS resolution**:
```bash
ping customer.mbd.local  # Should resolve to 127.0.0.1
```

---

## 11. Configure Keycloak Realm

Once Keycloak is running, configure the MBD realm with clients and roles:

```bash
# Make script executable
chmod +x infrastructure/k8s/keycloak/configure-realm.sh

# Run configuration script
./infrastructure/k8s/keycloak/configure-realm.sh
```

This creates:
- **Realm**: `mbd`
- **Clients**: `customer-frontend` (public, PKCE), `admin-frontend` (public, requires admin role)
- **Realm Roles**: `admin` role for admin-frontend access
- **Protocol Mapper**: Adds `roles` claim to JWT tokens

---

## 12. Access Applications

Once everything is deployed and configured:

| Application | URL | Credentials |
|------------|-----|------------|
| Customer Frontend | https://customer.mbd.local | Register new account |
| Admin Frontend | https://admin.mbd.local | Requires `admin` role |
| Keycloak Admin | https://keycloak.mbd.local/admin | admin / (from keycloak-secret) |
| Kafbat UI | https://kafbat.mbd.local | Kafka topic browser |
| ArgoCD UI | https://localhost:8081 | admin / (from step 4) |

**Create an admin user**:
1. Register a new account via Customer Frontend
2. Login to Keycloak admin console
3. Go to Users → Select your user → Role mappings → Assign roles
4. Add `admin` realm role
5. Logout and login again to Admin Frontend

---

## Summary of Manual vs. Automated

| Component | Method | Reason |
| :--- | :--- | :--- |
| **Kind Cluster** | Manual | External to Kubernetes - cluster must exist first |
| **Istio Control Plane** | Manual | Required BEFORE ArgoCD deploys apps with VirtualServices |
| **cert-manager** | Manual | Required BEFORE ArgoCD deploys apps with Certificates |
| **ArgoCD** | Manual | The GitOps orchestrator - must be installed to manage other apps |
| **Development Secrets** | Manual | Required before apps start - not managed by Git for security |
| **Docker Images** | Manual | Kind requires explicit image loading |
| **Everything Else** | **Automated** | Managed via GitOps (ArgoCD root app pattern) |

---

## FAQ

### Q: Why does the order matter?

**A**: ArgoCD cannot deploy applications that reference CRDs (Custom Resource Definitions) that don't exist yet. Istio provides VirtualService/Gateway/DestinationRule CRDs, and cert-manager provides Certificate/ClusterIssuer CRDs. Installing them first ensures ArgoCD can sync all applications successfully.

### Q: What if I already ran ArgoCD before installing Istio?

**A**: The ArgoCD apps will show `SyncFailed` with errors about missing API resources. After installing Istio and cert-manager, manually trigger a sync:
```bash
kubectl patch application mbd-keycloak -n argocd --type merge -p '{"operation":{"initiatedBy":{"username":"admin"},"sync":{}}}'
```

### Q: How do I rebuild and reload a single service?

**A**:
```bash
# Rebuild
cd backend/<service-name>
docker build -t <service-name>:latest .

# Reload into Kind
kind load docker-image <service-name>:latest --name single-node

# Restart deployment
kubectl rollout restart deployment <service-name> -n mbd
```

### Q: Can I use this with OrbStack Kubernetes instead of Kind?

**A**: Yes, but skip steps 2 and 8.3 (cluster creation and image loading). OrbStack's Kubernetes has direct access to Docker images. The DNS configuration in step 10 will use the OrbStack ingress IP instead of 127.0.0.1.

### Q: How do I reset everything and start over?

**A**:
```bash
# Delete Kind cluster
kind delete cluster --name single-node

# Start from step 2
```

---

## Troubleshooting

### ArgoCD shows "OutOfSync" for all apps
- **Cause**: Git repository not accessible or not configured
- **Fix**: Check `argocd repo list` - repository should show "Successful" connection status

### Pods stuck in "ImagePullBackOff" or "ErrImageNeverPull"
- **Cause**: Images not loaded into Kind cluster before deploying
- **Fix**: Run step 7.3 to load images, then restart: `kubectl rollout restart deployment -n mbd`

### Keycloak pod in CrashLoopBackOff
- **Cause**: `keycloak-postgresql-0` pod not running
- **Fix**: Check `kubectl get pods -n mbd-infra | grep postgresql`. If missing, ArgoCD didn't sync properly - check ArgoCD app status.

### Services can't reach each other
- **Cause**: Istio mTLS not configured or PeerAuthentication issue
- **Fix**: Check `kubectl get peerauthentication -n mbd` and verify Istio sidecar injection: `kubectl get pods -n mbd -o jsonpath='{.items[*].spec.containers[*].name}'` should show `istio-proxy` container.

### Can't access applications via browser
- **Cause**: `/etc/hosts` not configured or Istio Ingress Gateway not running
- **Fix**:
  1. Verify `/etc/hosts` entries
  2. Check `kubectl get pods -n istio-system` - ingress gateway should be Running
  3. Check `kubectl get virtualservices -n mbd-infra` - should show keycloak, customer-frontend, admin-frontend VirtualServices

---

## Next Steps

With the cluster bootstrapped and all applications running:

1. **Test the application**: Register a user at https://customer.mbd.local
2. **Explore Kafka topics**: Visit https://kafbat.mbd.local to see fund price updates
3. **Test admin features**: Assign yourself admin role and access https://admin.mbd.local
4. **Monitor ArgoCD**: Keep https://localhost:8081 open to watch GitOps in action

For development workflows and testing, see:
- `doc/backend-testing.md` - Running integration tests
- `doc/operation-notes.md` - Day-to-day operations
- `doc/architecture.md` - System architecture details
