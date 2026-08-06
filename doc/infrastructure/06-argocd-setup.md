# ArgoCD Setup

This guide explains how to install and configure ArgoCD for GitOps deployment to GitHub.

## Prerequisites

- Kind cluster running
- mbd and mbd-infra namespaces created
- GitHub account and repository created for manifests
- kubectl configured to use the Kind cluster
- Cluster admin permissions
- GitHub personal access token with repo permissions

## Important: ArgoCD Bootstrap

ArgoCD is the GitOps tool that manages your infrastructure resources. It is installed directly via kubectl (or can be bootstrapped via GitOps itself). The other infrastructure components (namespaces, PostgreSQL, Kafka, Keycloak, etc.) are managed by ArgoCD through manifests in your GitHub repository.

## Steps

### 1. Install ArgoCD

```bash
# Create ArgoCD namespace
kubectl create namespace argocd

# Install ArgoCD
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Verify installation
kubectl get pods -n argocd
```

### 2. Access ArgoCD UI

```bash
# Port forward to access ArgoCD UI
kubectl port-forward svc/argocd-server -n argocd 8080:443

# Access the UI at: https://localhost:8080
# Initial admin password:
argocd admin initial-password -n argocd
```

### 3. Login to ArgoCD CLI

```bash
# Install ArgoCD CLI
brew install argocd  # macOS
# Or download from: https://github.com/argoproj/argo-cd/releases

# Login
argocd login localhost:8080 --insecure

# Update admin password (recommended)
argocd account update-password
```

### 4. Configure GitHub Repository Access

#### Option A: Using SSH Keys

```bash
# Generate SSH key for ArgoCD
ssh-keygen -t rsa -b 4096 -C "argocd@mbd" -f /tmp/argocd-ssh-key

# Add the public key to your GitHub repository
cat /tmp/argocd-ssh-key.pub
# Copy and add to GitHub repo Settings > Deploy Keys

# Create ArgoCD secret for SSH
kubectl create secret generic argocd-repo-ssh \
  --from-file=sshPrivateKey=/tmp/argocd-ssh-key \
  --from-file=known_hosts=/dev/null \
  -n argocd
```

#### Option B: Using Personal Access Token

```bash
# Create ArgoCD secret for GitHub token
kubectl create secret generic github-token \
  --from-literal=username=your-github-username \
  --from-literal=password=your-github-pat \
  -n argocd
```

### 5. Add GitHub Repository to ArgoCD

```bash
# Add repository (replace with your repo URL)
argocd repo add git@github.com:your-username/mbd-manifests.git \
  --ssh-private-key-path /tmp/argocd-ssh-key

# Or using HTTPS with token
argocd repo add https://github.com/your-username/mbd-manifests.git \
  --username your-github-username \
  --password your-github-pat

# Verify repository
argocd repo list
```

### 6. Create ArgoCD Applications

The individual infrastructure components have their own ArgoCD applications as defined in their respective setup guides:

- **mbd-namespaces** - Manages namespace, resource quota, and network policy manifests
- **mbd-istio** - Manages Istio PeerAuthentication, Gateway, and DestinationRules
- **mbd-postgresql** - Manages PostgreSQL StatefulSet, PVC, and service
- **mbd-kafka** - Manages Kafka StatefulSet, PVC, ConfigMap, and service
- **mbd-keycloak** - Manages Keycloak deployment, PostgreSQL, and VirtualService

These applications are created by applying the manifests from the respective setup guides:

```bash
# Apply all ArgoCD applications
kubectl apply -f infrastructure/argocd/namespaces-app.yaml
kubectl apply -f infrastructure/argocd/istio-app.yaml
kubectl apply -f infrastructure/argocd/postgresql-app.yaml
kubectl apply -f infrastructure/argocd/kafka-app.yaml
kubectl apply -f infrastructure/argocd/keycloak-app.yaml
```

### 7. Create ArgoCD Application for Services (Future)

When you create the application services, create an application for them:

```yaml
# infrastructure/argocd/services-app.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: mbd-services
  namespace: argocd
spec:
  project: default
  source:
    repoURL: git@github.com:your-username/mbd-manifests.git
    targetRevision: main
    path: services
  destination:
    server: https://kubernetes.default.svc
    namespace: mbd
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

Apply the application:

```bash
kubectl apply -f infrastructure/argocd/services-app.yaml
```

### 8. Create ArgoCD Project (Optional)

For better organization, create a dedicated project:

```yaml
# infrastructure/argocd/project.yaml
apiVersion: argoproj.io/v1alpha1
kind: AppProject
metadata:
  name: mbd
  namespace: argocd
spec:
  description: MBD Banking Application
  sourceRepos:
    - '*'
  destinations:
    - namespace: mbd
      server: https://kubernetes.default.svc
    - namespace: mbd-infra
      server: https://kubernetes.default.svc
  clusterResourceWhitelist:
    - group: '*'
      kind: '*'
```

Apply the project:

```bash
kubectl apply -f infrastructure/argocd/project.yaml
```

Update applications to use the project by changing `project: default` to `project: mbd` in each application manifest.

### 9. Verify ArgoCD Setup

```bash
# Check ArgoCD pods
kubectl get pods -n argocd

# Check applications
argocd app list

# Check application status
argocd app get mbd-namespaces
argocd app get mbd-istio
argocd app get mbd-postgresql
argocd app get mbd-kafka
argocd app get mbd-keycloak

# Check sync status
argocd app sync mbd-namespaces
argocd app sync mbd-istio
argocd app sync mbd-postgresql
argocd app sync mbd-kafka
argocd app sync mbd-keycloak
```

### 10. Set Up GitHub Webhook (Optional)

For automatic sync on git push:

```bash
# Get ArgoCD webhook URL
argocd repo get git@github.com:your-username/mbd-manifests.git

# Add webhook to GitHub repository
# Settings > Webhooks > Add webhook
# Use the URL from above
```

### 11. Configure ArgoCD Notifications (Optional)

Install ArgoCD Notifications for Slack/email notifications:

```bash
# Install ArgoCD Notifications
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/notifications-install.yaml

# Configure notification templates and services
# See: https://argoproj.github.io/argo-cd/operator-manual/notifications/
```

## GitHub Repository Structure

Create the following structure in your GitHub repository:

```
mbd-manifests/
├── infrastructure/
│   ├── k8s/
│   │   ├── namespaces.yaml
│   │   ├── resource-quota.yaml
│   │   ├── network-policy.yaml
│   │   ├── istio/
│   │   │   ├── peer-authentication.yaml
│   │   │   ├── gateway.yaml
│   │   │   └── destination-rules.yaml
│   │   ├── postgresql/
│   │   │   ├── storage-class.yaml
│   │   │   ├── pvc.yaml
│   │   │   ├── statefulset.yaml
│   │   │   └── service.yaml
│   │   ├── kafka/
│   │   │   ├── storage-class.yaml
│   │   │   ├── pvc.yaml
│   │   │   ├── configmap.yaml
│   │   │   ├── statefulset.yaml
│   │   │   └── service.yaml
│   │   └── keycloak/
│   │       ├── storage-class.yaml
│   │       ├── pvc.yaml
│   │       ├── postgresql-statefulset.yaml
│   │       ├── postgresql-service.yaml
│   │       ├── deployment.yaml
│   │       ├── service.yaml
│   │       └── virtualservice.yaml
│   └── argocd/
│       ├── namespaces-app.yaml
│       ├── istio-app.yaml
│       ├── postgresql-app.yaml
│       ├── kafka-app.yaml
│       ├── keycloak-app.yaml
│       └── project.yaml (optional)
└── services/
    ├── user-service/
    │   ├── deployment.yaml
    │   ├── service.yaml
    │   └── virtualservice.yaml
    ├── account-service/
    │   ├── deployment.yaml
    │   ├── service.yaml
    │   └── virtualservice.yaml
    ├── fund-service/
    │   ├── deployment.yaml
    │   ├── service.yaml
    │   └── virtualservice.yaml
    ├── portfolio-service/
    │   ├── deployment.yaml
    │   ├── service.yaml
    │   └── virtualservice.yaml
    ├── admin-service/
    │   ├── deployment.yaml
    │   ├── service.yaml
    │   └── virtualservice.yaml
    ├── customer-frontend/
    │   ├── deployment.yaml
    │   ├── service.yaml
    │   └── virtualservice.yaml
    └── admin-frontend/
        ├── deployment.yaml
        ├── service.yaml
        └── virtualservice.yaml
```

## Cleanup

To remove ArgoCD:

```bash
# Delete all infrastructure applications
argocd app delete mbd-namespaces
argocd app delete mbd-istio
argocd app delete mbd-postgresql
argocd app delete mbd-kafka
argocd app delete mbd-keycloak

# Delete services application (if created)
argocd app delete mbd-services

# Remove repository
argocd repo rm git@github.com:your-username/mbd-manifests.git

# Delete ArgoCD installation
kubectl delete -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml

# Delete ArgoCD namespace
kubectl delete namespace argocd
```

## Verification

Run these commands to verify the setup:

```bash
# Check ArgoCD is running
kubectl get pods -n argocd

# Check applications are synced
argocd app list

# Check application health
argocd app get mbd-infrastructure
argocd app get mbd-services

# Check ArgoCD UI
kubectl port-forward svc/argocd-server -n argocd 8080:443
# Open https://localhost:8080
```

## Troubleshooting

### Repository Connection Issues

```bash
# Test repository connection
argocd repo get git@github.com:your-username/mbd-manifests.git

# Check SSH key secret
kubectl get secret argocd-repo-ssh -n argocd

# Check GitHub token secret
kubectl get secret github-token -n argocd

# Test SSH connection
ssh -i /tmp/argocd-ssh-key -T git@github.com
```

### Sync Failures

```bash
# Check application status
argocd app get mbd-infrastructure

# Check sync status
argocd app sync mbd-infrastructure --dry-run

# Check application logs
argocd app logs mbd-infrastructure

# Force sync
argocd app sync mbd-infrastructure --force
```

### Application Not Syncing

```bash
# Check sync policy
argocd app get mbd-infrastructure -o yaml

# Check auto-sync is enabled
argocd app set mbd-infrastructure --sync-policy automated

# Check self-heal
argocd app set mbd-infrastructure --self-heal

# Check prune
argocd app set mbd-infrastructure --auto-prune
```

### Webhook Issues

```bash
# Check webhook configuration
argocd repo get git@github.com:your-username/mbd-manifests.git

# Test webhook manually
curl -X POST <webhook-url>

# Check ArgoCD server logs
kubectl logs -n argocd -l app.kubernetes.io/name=argocd-server
```

## Best Practices

- Use separate branches for development and production
- Implement proper RBAC for ArgoCD
- Use sealed secrets for sensitive data
- Implement proper health checks for applications
- Use project-level resource restrictions
- Enable audit logging
- Regularly backup ArgoCD configuration
- Use proper Git branching strategy
- Implement proper CI/CD pipeline integration
- Monitor ArgoCD performance and resource usage
