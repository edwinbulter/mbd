# Namespace Setup

This guide explains how to set up the Kubernetes namespaces for the MBD project using manifests that will be managed by ArgoCD.

## Prerequisites

- Existing Kind cluster running
- kubectl configured to use the Kind cluster
- Cluster admin permissions

## Namespaces

The MBD project uses two dedicated namespaces:

- `mbd` - Application namespace for microservices and frontends
- `mbd-infra` - Infrastructure namespace for PostgreSQL, Kafka, and Keycloak

## Important: ArgoCD Management

**All Kubernetes resources should be managed through manifests in your GitHub repository, not through kubectl commands.** This ensures ArgoCD can track and manage all resources without drift. Any resources created via kubectl will not be in Git and may be removed or cause conflicts when ArgoCD syncs.

## Steps

### 1. Create Namespace Manifest

The namespace manifest file `infrastructure/k8s/namespaces.yaml` has already been created with the following content:

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

**Important:** This file must be committed to your GitHub repository in the manifests repository so ArgoCD can manage these namespaces.

Apply the manifest:

```bash
kubectl apply -f infrastructure/k8s/namespaces.yaml
```

### 2. Verify Namespaces

```bash
# List all namespaces
kubectl get namespaces

# Verify the new namespaces exist
kubectl get namespace mbd
kubectl get namespace mbd-infra

# Verify Istio labels
kubectl get namespace mbd -L istio-injection
kubectl get namespace mbd-infra -L istio-injection
```

### 3. Set Default Namespace (Optional)

```bash
# Set mbd as default namespace for current context
kubectl config set-context --current --namespace=mbd

# Verify current namespace
kubectl config view --minify | grep namespace
```

### 4. Configure Resource Quotas

The resource quota file `infrastructure/k8s/resource-quota.yaml` has already been created with the following content:

```yaml
apiVersion: v1
kind: ResourceQuota
metadata:
  name: mbd-quota
  namespace: mbd
spec:
  hard:
    requests.cpu: "4"
    requests.memory: 8Gi
    limits.cpu: "8"
    limits.memory: 16Gi
    persistentvolumeclaims: "10"
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the resource quota:

```bash
kubectl apply -f infrastructure/k8s/resource-quota.yaml
```

### 5. Configure Network Policies

The network policy file `infrastructure/k8s/network-policy.yaml` has already been created with the following content:

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: mbd-network-policy
  namespace: mbd
spec:
  podSelector: {}
  policyTypes:
    - Ingress
    - Egress
  ingress:
    - from:
        - namespaceSelector:
            matchLabels:
              name: mbd
        - namespaceSelector:
            matchLabels:
              name: mbd-infra
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              name: mbd
        - namespaceSelector:
            matchLabels:
              name: mbd-infra
    - to:
        - namespaceSelector: {}
      ports:
        - protocol: TCP
          port: 53
        - protocol: UDP
          port: 53
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the network policy:

```bash
kubectl apply -f infrastructure/k8s/network-policy.yaml
```

### 6. Commit Manifests to GitHub

Add all manifest files to your GitHub manifests repository:

```bash
# Add files to git
git add infrastructure/k8s/namespaces.yaml
git add infrastructure/k8s/resource-quota.yaml
git add infrastructure/k8s/network-policy.yaml

# Commit and push
git commit -m "Add namespace manifests for MBD project"
git push origin main
```

### 7. Configure ArgoCD Application

Create an ArgoCD application to manage these namespace resources:

```yaml
# infrastructure/argocd/namespaces-app.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: mbd-namespaces
  namespace: argocd
spec:
  project: default
  source:
    repoURL: git@github.com:your-username/mbd-manifests.git
    targetRevision: main
    path: infrastructure/k8s
  destination:
    server: https://kubernetes.default.svc
    namespace: default
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

Apply the ArgoCD application:

```bash
kubectl apply -f infrastructure/argocd/namespaces-app.yaml
```

## Cleanup

To delete the namespaces:

```bash
# Delete via ArgoCD (recommended)
argocd app delete mbd-namespaces

# Or delete directly (not recommended - ArgoCD will recreate)
kubectl delete namespace mbd
kubectl delete namespace mbd-infra
```

**Note:** Deleting namespaces directly will delete all resources in these namespaces. If managed by ArgoCD, it will recreate them on the next sync.

## Verification

Run these commands to verify the setup:

```bash
# Check namespaces exist
kubectl get namespaces | grep mbd

# Check resource quotas
kubectl get resourcequota -n mbd

# Check network policies
kubectl get networkpolicy -n mbd

# Check Istio labels
kubectl get namespace mbd -L istio-injection
kubectl get namespace mbd-infra -L istio-injection

# Check ArgoCD application status
argocd app get mbd-namespaces
```

## ArgoCD Integration

Once the manifests are in GitHub and the ArgoCD application is configured:

1. ArgoCD will continuously monitor the Git repository
2. Any changes to the manifests in Git will be automatically synced to the cluster
3. Manual changes via kubectl will be detected as drift and either reverted or flagged
4. All resources are version-controlled and auditable

## Troubleshooting

### ArgoCD Drift

If ArgoCD shows drift between Git and cluster:

```bash
# Check application status
argocd app get mbd-namespaces

# Sync application
argocd app sync mbd-namespaces

# Check for out-of-sync resources
argocd app diff mbd-namespaces
```

### Namespace Labels Missing

If Istio labels are missing:

```bash
# Check current labels
kubectl get namespace mbd -L istio-injection

# Update the manifest in Git (add labels section)
# Commit and push changes
# ArgoCD will automatically sync
```

### Resource Quota Issues

If resource quota is not applied:

```bash
# Check quota status
kubectl describe resourcequota mbd-quota -n mbd

# Check if namespace exists
kubectl get namespace mbd

# Sync ArgoCD application
argocd app sync mbd-namespaces
```
