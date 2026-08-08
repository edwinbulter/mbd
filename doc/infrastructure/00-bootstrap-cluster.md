# Cluster Bootstrap Manual

This guide describes how to set up a fresh Kubernetes cluster and deploy the complete MBD (My Bank Demo) environment using a GitOps-first approach with ArgoCD.

## Prerequisites

- **Kind**: For local cluster orchestration.
- **kubectl**: Kubernetes command-line tool.
- **istioctl**: Istio command-line tool.
- **ArgoCD CLI**: For repository and application management.
- **GitHub SSH Key**: Configured in your GitHub account.

---

## Phase 1: Infrastructure Bootstrap (Manual)

Some components must be installed manually to prepare the environment for ArgoCD.

### 1. Create the Cluster
```bash
kind create cluster --name mbd --config infrastructure/kind/config.yaml
```

### 2. Install Istio
Istio is best installed manually to ensure the control plane (`istiod`) and CRDs are ready.
```bash
istioctl install --set profile=default -y
```

### 3. Install cert-manager
Required for TLS certificates. Installing manually ensures CRDs are present before Issuers are applied by ArgoCD.
```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.3/cert-manager.yaml
```

### 4. Install PostgreSQL & Kafka (Optional Manual)
While ArgoCD manages these via the Root App, you might want to install them manually if you need specific volume management or debugging:
```bash
# Manual install examples (if not using GitOps for these)
kubectl apply -k infrastructure/k8s/postgresql
kubectl apply -k infrastructure/k8s/kafka
```
*Note: It is highly recommended to let ArgoCD handle these via the Root App.*

---

## Phase 2: GitOps Setup (Manual)

### 1. Install ArgoCD
```bash
k create ns argocd
k apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

### 2. Configure Repository Access
ArgoCD needs access to your repository. Use your GitHub SSH key:
```bash
# Create secret with your private key (replace id_ed25519 with your actual key file)
k -n argocd create secret generic argocd-repo-ssh --from-file=sshPrivateKey=$HOME/.ssh/id_ed25519

# Add the repository to ArgoCD
argocd repo add git@github.com:edwinbulter/mbd.git --ssh-private-key-path $HOME/.ssh/id_ed25519
```

### 3. Create Development Secrets (Required)
Several services (PostgreSQL, Keycloak, Backend Services) require secrets that are **not** stored in Git for security reasons. Run the following script to create them for your development environment:

```bash
chmod +x infrastructure/scripts/create-dev-secrets.sh
./infrastructure/scripts/create-dev-secrets.sh
```

*Note: In production, these should be managed by a Secret Manager or SealedSecrets.*

---

## Phase 3: Automated Deployment (The Root App)

Now that ArgoCD is ready, we use the **App-of-Apps** pattern to deploy the rest.

### 1. Apply the Root Application
```bash
# Apply from the root of the infrastructure folder
k apply -f infrastructure/root-app.yaml
```

### 2. Managed Components
ArgoCD will automatically create and sync:
- **Namespaces**: `mbd` and `mbd-infra`.
- **Infrastructure Services**: PostgreSQL, Kafka, Keycloak, cert-manager config.
- **Istio Resources**: Gateway, VirtualServices, AuthorizationPolicies.
- **Applications**: All backend microservices and frontends.

---

## Phase 4: Final Verification

### 1. Check App Status
```bash
argocd app list
```

### 2. Configure Local DNS
Add the following to your `/etc/hosts`:
```text
127.0.0.1 customer.mbd.local admin.mbd.local keycloak.mbd.local
```

### 3. Keycloak Configuration
Run the automated configuration script once Keycloak is healthy:
```bash
./infrastructure/k8s/keycloak/configure-realm.sh
```

---

## Summary of Manual vs. Automated

| Component | Method | Reason |
| :--- | :--- | :--- |
| **Kind Cluster** | Manual | External to the cluster. |
| **Istio Control Plane** | Manual | Requires `istioctl` for reliable profile management. |
| **cert-manager** | Manual | Simplifies CRD management before Issuers are applied. |
| **ArgoCD** | Manual | The orchestrator itself. |
| **Everything Else** | **Automated** | Managed via GitOps in `infrastructure/argocd/`. |
