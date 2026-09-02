# Infrastructure Cleanup

This guide describes how to clean up all MBD infrastructure components while preserving the Kind cluster itself. After cleanup, you can redeploy from scratch starting at step 3 of [00-bootstrap-cluster.md](00-bootstrap-cluster.md).

---

## What Gets Deleted

- ✅ All applications (account-service, portfolio-service, fund-service, user-service, admin-service)
- ✅ All frontends (customer-frontend, admin-frontend)
- ✅ All infrastructure (PostgreSQL, Kafka, Keycloak, Kafbat UI)
- ✅ All persistent data (databases, Kafka topics, Keycloak users)
- ✅ ArgoCD and all applications
- ✅ Istio service mesh
- ✅ cert-manager and certificates
- ✅ All namespaces: `mbd`, `mbd-infra`, `argocd`, `istio-system`, `cert-manager`

## What Remains

- ❌ Kind cluster (`single-node`)
- ❌ Docker images (cached locally)
- ❌ Local source code and manifests
- ❌ `/etc/hosts` entries

---

## Cleanup Steps

### 1. Delete ArgoCD Namespace

Delete ArgoCD first to prevent it from trying to reconcile resources:

```bash
# Delete ArgoCD namespace
kubectl delete namespace argocd
```

**Wait for ArgoCD to be fully removed**:

```bash
# Monitor ArgoCD namespace deletion
kubectl get namespace argocd
# Should return: Error from server (NotFound)
```

---

### 2. Delete Remaining Namespaces

Now delete all application and infrastructure namespaces:

```bash
# Delete application and infrastructure namespaces
kubectl delete namespace mbd mbd-infra

# Delete Istio
kubectl delete namespace istio-system

# Delete cert-manager
kubectl delete namespace cert-manager
```

**Wait for deletion to complete** (may take 1-2 minutes):

```bash
# Monitor namespace deletion
watch kubectl get namespaces
```

---

### 3. Uninstall Istio

```bash
# Uninstall Istio components
istioctl uninstall --purge -y

# Verify Istio is removed
kubectl get all -n istio-system
# Should return: No resources found
```

---

### 4. Clean Up Persistent Volumes

Check for any orphaned PVCs or PVs:

```bash
# List all PVCs (should be empty after namespace deletion)
kubectl get pvc --all-namespaces

# List all PVs
kubectl get pv

# If any PVs remain in "Released" state, delete them
kubectl delete pv <pv-name>
```

---

### 5. Verification

Verify the cluster is clean:

```bash
# Check namespaces (should only show kube-* and default)
kubectl get namespaces

# Check all pods
kubectl get pods --all-namespaces

# Check all services
kubectl get svc --all-namespaces

# Check all PVCs
kubectl get pvc --all-namespaces

# Check all PVs
kubectl get pv
```

Expected state:
- Only `default`, `kube-system`, `kube-public`, `kube-node-lease`, `local-path-storage` namespaces remain
- No mbd, mbd-infra, argocd, istio-system, or cert-manager namespaces

---

## Troubleshooting

### Namespace Stuck in "Terminating"

```bash
# Force delete the namespace
kubectl delete namespace <namespace-name> --force --grace-period=0

# If still stuck, remove finalizers
kubectl get namespace <namespace-name> -o json | \
  jq '.spec.finalizers = []' | \
  kubectl replace --raw /api/v1/namespaces/<namespace-name>/finalize -f -
```

### PVC Won't Delete

```bash
# Find which pod is using the PVC
kubectl describe pvc <pvc-name> -n <namespace>

# Delete the pod first
kubectl delete pod <pod-name> -n <namespace> --force --grace-period=0

# Then delete PVC
kubectl delete pvc <pvc-name> -n <namespace> --force --grace-period=0
```

### ArgoCD Application Won't Delete

```bash
# Remove finalizers
kubectl patch app <app-name> -n argocd \
  -p '{"metadata":{"finalizers":[]}}' --type merge

# Force delete
kubectl delete app <app-name> -n argocd --force --grace-period=0
```

---

## After Cleanup

### Redeploy from Scratch

Start from **step 3** of the bootstrap guide (skip cluster creation):

1. Go to [00-bootstrap-cluster.md](00-bootstrap-cluster.md)
2. Skip steps 1-2 (cluster already exists)
3. Continue from **step 3: Install Core Infrastructure**

### Verify Cluster Health

Before redeploying, verify the cluster is healthy:

```bash
# Check cluster info
kubectl cluster-info

# Check node status
kubectl get nodes

# Check system pods
kubectl get pods -n kube-system
```

---

## Quick Reference

### Automated Cleanup Script (Recommended)

Use the provided cleanup script for a safe, interactive cleanup:

```bash
# Run the cleanup script
./infrastructure/scripts/cleanup.sh
```

The script will:
- Prompt for confirmation before deleting anything
- Show progress for each step
- Verify cleanup completion
- Provide next steps for redeployment

---

### Manual Cleanup (Command Block)

```bash
# Delete ArgoCD namespace first
kubectl delete namespace argocd

# Wait for ArgoCD to be removed
echo "Waiting for ArgoCD to terminate..."
while kubectl get namespace argocd 2>/dev/null; do
  sleep 5
done

# Delete remaining namespaces
kubectl delete namespace mbd mbd-infra istio-system cert-manager

# Uninstall Istio
istioctl uninstall --purge -y

# Wait for cleanup to complete
echo "Waiting for namespaces to terminate..."
while kubectl get namespace mbd mbd-infra istio-system cert-manager 2>/dev/null; do
  sleep 5
done

# Clean up orphaned PVs
kubectl delete pv $(kubectl get pv -o name 2>/dev/null) 2>/dev/null || true

echo "Cleanup complete! Cluster is ready for redeployment."
```

### Verification One-Liner

```bash
kubectl get namespaces,pvc,pv --all-namespaces | grep -E 'mbd|argocd|istio|cert-manager' || echo "Clean - no MBD resources found"
```

---

## Notes

- The Kind cluster `single-node` remains intact
- Docker images remain cached (saves time on rebuild)
- `/etc/hosts` entries can remain (still valid for new deployment)
- Cluster is ready to run bootstrap from step 3 onwards
