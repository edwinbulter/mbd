# Cluster Bootstrap Manual (Orbstack/GitOps)

This guide describes how to set up the MBD (My Bank Demo) environment on an Orbstack Kubernetes cluster using the **App-of-Apps** (Root App) pattern.

## 1. Prerequisites

- **Orbstack**: Kubernetes enabled in settings.
- **kubectl**, **istioctl**, **argocd CLI**.
- **GitHub SSH Key**: Configured in your GitHub account.

---

## 2. Infrastructure Setup (Manual)

Orbstack handles the cluster creation, but we need to install the core control planes manually.

### Install Istio
```bash
istioctl install --set profile=default -y
```

### Install cert-manager
```bash
kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.13.3/cert-manager.yaml
```

### Install PostgreSQL & Kafka (Optional Manual)
While ArgoCD manages these via the Root App, you might want to install them manually if you need specific volume management or debugging:
```bash
# Manual install examples (if not using GitOps for these)
kubectl apply -k infrastructure/k8s/postgresql
kubectl apply -k infrastructure/k8s/kafka
```
*Note: It is highly recommended to let ArgoCD handle these via the Root App.*

---

## 3. GitOps Setup (Manual)

### Install ArgoCD
```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

### Configure Repository Access
```bash
# Add your SSH key (replace id_ed25519 with your actual key file)
kubectl -n argocd create secret generic argocd-repo-ssh --from-file=sshPrivateKey=$HOME/.ssh/id_ed25519

# Register repo in ArgoCD
argocd repo add git@github.com:edwinbulter/mbd.git --ssh-private-key-path $HOME/.ssh/id_ed25519
```

### Create Development Secrets
These are required for Keycloak and PostgreSQL to start.
```bash
chmod +x infrastructure/scripts/create-dev-secrets.sh
./infrastructure/scripts/create-dev-secrets.sh
```

---

## 4. Automated Deployment (The Root App)

Apply the Root App to deploy all namespaces, services, and frontends.
```bash
kubectl apply -f infrastructure/root-app.yaml
```

---

## 5. Final Configuration & Verification

### Configure Local DNS
Find your Ingress EXTERNAL-IP:
```bash
kubectl get svc -n istio-system istio-ingressgateway
```
Update your `/etc/hosts` with that IP (e.g., `192.168.139.2`):
```text
192.168.139.2 customer.mbd.local admin.mbd.local keycloak.mbd.local
```

### Keycloak Realm Configuration
Once Keycloak is running:
```bash
./infrastructure/k8s/keycloak/configure-realm.sh
```

---

## FAQ

**Q: What is `infrastructure/kind/config.yaml`?**
A: That file was used to configure a **Kind** (Kubernetes in Docker) cluster. It handled port mappings and node labels specific to Kind. Since you are using **Orbstack**, this file is redundant and can be ignored.

**Q: Why do I need to run `configure-realm.sh`?**
A: While ArgoCD manages the *deployment* of Keycloak, it does not manage the *internal configuration* (realms, clients, roles). This script automates that initial setup via the Keycloak API.

---

## Summary of Manual vs. Automated

| Component | Method | Reason |
| :--- | :--- | :--- |
| **Cluster Management** | Manual | External to the cluster (Orbstack). |
| **Istio Control Plane** | Manual | Requires `istioctl` for reliable profile management. |
| **cert-manager** | Manual | Simplifies CRD management before Issuers are applied. |
| **ArgoCD** | Manual | The orchestrator itself. |
| **Everything Else** | **Automated** | Managed via GitOps in `infrastructure/argocd/`. |
