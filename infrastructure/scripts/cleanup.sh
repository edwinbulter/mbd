#!/bin/bash

# MBD Infrastructure Cleanup Script
# This script removes all MBD infrastructure while preserving the Kind cluster

set -e  # Exit on error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored output
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check required tools
check_requirements() {
    print_info "Checking required tools..."

    if ! command -v kubectl &> /dev/null; then
        print_error "kubectl not found. Please install kubectl."
        exit 1
    fi

    if ! command -v istioctl &> /dev/null; then
        print_warn "istioctl not found. Istio uninstall will be skipped."
    fi
}

# Confirmation prompt
confirm_cleanup() {
    echo ""
    print_warn "=========================================="
    print_warn "MBD INFRASTRUCTURE CLEANUP"
    print_warn "=========================================="
    echo ""
    print_warn "This will DELETE:"
    echo "  - ArgoCD namespace and all applications"
    echo "  - mbd namespace (all backend services and frontends)"
    echo "  - mbd-infra namespace (PostgreSQL, Kafka, Keycloak)"
    echo "  - istio-system namespace"
    echo "  - cert-manager namespace"
    echo "  - All persistent data (databases, Kafka topics)"
    echo ""
    print_info "This will PRESERVE:"
    echo "  - Kind cluster 'single-node'"
    echo "  - Docker images"
    echo "  - Local source code"
    echo ""

    read -p "Are you sure you want to proceed? (yes/no): " -r
    echo
    if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
        print_info "Cleanup cancelled."
        exit 0
    fi
}

# Delete ArgoCD namespace
delete_argocd() {
    print_info "Step 1/5: Deleting ArgoCD namespace..."

    if kubectl get namespace argocd &> /dev/null; then
        kubectl delete namespace argocd

        # Wait for ArgoCD to be removed
        print_info "Waiting for ArgoCD namespace to terminate..."
        while kubectl get namespace argocd &> /dev/null; do
            echo -n "."
            sleep 2
        done
        echo ""
        print_info "ArgoCD namespace deleted."
    else
        print_warn "ArgoCD namespace not found, skipping."
    fi
}

# Delete remaining namespaces
delete_namespaces() {
    print_info "Step 2/5: Deleting remaining namespaces..."

    NAMESPACES=("mbd" "mbd-infra" "istio-system" "cert-manager")

    for ns in "${NAMESPACES[@]}"; do
        if kubectl get namespace "$ns" &> /dev/null; then
            print_info "Deleting namespace: $ns"
            kubectl delete namespace "$ns"
        else
            print_warn "Namespace $ns not found, skipping."
        fi
    done

    # Wait for namespaces to terminate
    print_info "Waiting for namespaces to terminate..."
    while kubectl get namespace mbd mbd-infra istio-system cert-manager &> /dev/null; do
        echo -n "."
        sleep 2
    done
    echo ""
    print_info "All namespaces deleted."
}

# Uninstall Istio
uninstall_istio() {
    print_info "Step 3/5: Uninstalling Istio..."

    if command -v istioctl &> /dev/null; then
        istioctl uninstall --purge -y
        print_info "Istio uninstalled."
    else
        print_warn "istioctl not found, skipping Istio uninstall."
    fi
}

# Clean up orphaned PVs
cleanup_pvs() {
    print_info "Step 4/5: Cleaning up orphaned persistent volumes..."

    PVS=$(kubectl get pv -o name 2>/dev/null || true)

    if [ -n "$PVS" ]; then
        print_info "Found persistent volumes, deleting..."
        kubectl delete pv --all 2>/dev/null || true
        print_info "Persistent volumes cleaned up."
    else
        print_info "No persistent volumes found."
    fi
}

# Verify cleanup
verify_cleanup() {
    print_info "Step 5/5: Verifying cleanup..."
    echo ""

    # Check namespaces
    print_info "Remaining namespaces:"
    kubectl get namespaces | grep -E 'NAME|default|kube-' || true
    echo ""

    # Check for any MBD resources
    MBD_RESOURCES=$(kubectl get namespaces,pvc,pv --all-namespaces 2>/dev/null | grep -iE 'mbd|argocd|istio|cert-manager' || true)

    if [ -z "$MBD_RESOURCES" ]; then
        print_info "✓ No MBD resources found - cleanup successful!"
    else
        print_warn "Some MBD resources still exist:"
        echo "$MBD_RESOURCES"
    fi

    echo ""
    print_info "Cluster status:"
    kubectl cluster-info
}

# Main execution
main() {
    echo ""
    print_info "MBD Infrastructure Cleanup Script"
    echo ""

    check_requirements
    confirm_cleanup

    echo ""
    delete_argocd
    delete_namespaces
    uninstall_istio
    cleanup_pvs
    verify_cleanup

    echo ""
    print_info "=========================================="
    print_info "Cleanup complete!"
    print_info "=========================================="
    echo ""
    print_info "Next steps:"
    echo "  1. Cluster 'single-node' is still running"
    echo "  2. To redeploy, run: kubectl apply -f infrastructure/k8s/namespaces.yaml"
    echo "  3. Then continue from step 3 of doc/infrastructure/00-bootstrap-cluster.md"
    echo ""
}

# Run main function
main
