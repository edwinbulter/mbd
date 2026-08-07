# PostgreSQL Setup

This guide explains how to deploy PostgreSQL for the MBD project.

## Prerequisites

- Kind cluster running
- mbd-infra namespace created (from 01-namespace-setup.md)
- Istio installed (from 02-istio-setup.md)
- kubectl configured to use the Kind cluster
- Cluster admin permissions

## Important: ArgoCD Management

**All Kubernetes resources should be managed through manifests in your GitHub repository, not through kubectl commands.** This ensures ArgoCD can track and manage all resources without drift. Secrets should be managed separately using sealed secrets or external secret management.

## Steps

### 1. Create Storage Class

The storage class manifest file `infrastructure/k8s/postgresql/storage-class.yaml` has already been created with the following content:

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: postgresql-storage
provisioner: rancher.io/local-path
reclaimPolicy: Retain
volumeBindingMode: WaitForFirstConsumer
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the storage class:

```bash
kubectl apply -f infrastructure/k8s/postgresql/storage-class.yaml
```

### 2. Create PostgreSQL Secret

Create a secret for PostgreSQL credentials. **Note:** Secrets should be managed using sealed secrets or external secret management in production. For development, you can create the secret manually:

```bash
# Generate a strong password
POSTGRES_PASSWORD=$(openssl rand -base64 32)

# Create the secret
kubectl create secret generic postgresql-secret \
  --from-literal=postgres-password=$POSTGRES_PASSWORD \
  --from-literal=postgres-user=mbdadmin \
  --from-literal=postgres-db=mbd \
  -n mbd-infra

# Verify the secret
kubectl get secret postgresql-secret -n mbd-infra

# Save the password for reference
echo "PostgreSQL Password: $POSTGRES_PASSWORD" > /tmp/postgresql-password.txt
```

**Important:** This secret is not managed by ArgoCD. For production, use sealed-secrets or external secret management.

### 3. Create Persistent Volume Claim

The PVC manifest file `infrastructure/k8s/postgresql/pvc.yaml` has already been created with the following content:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgresql-pvc
  namespace: mbd-infra
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: postgresql-storage
  resources:
    requests:
      storage: 10Gi
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the PVC:

```bash
kubectl apply -f infrastructure/k8s/postgresql/pvc.yaml
```

### 4. Deploy PostgreSQL StatefulSet

The StatefulSet manifest file `infrastructure/k8s/postgresql/statefulset.yaml` has already been created with the following content:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: postgresql
  namespace: mbd-infra
spec:
  serviceName: postgresql
  replicas: 1
  selector:
    matchLabels:
      app: postgresql
  template:
    metadata:
      labels:
        app: postgresql
    spec:
      containers:
        - name: postgresql
          image: postgres:15-alpine
          ports:
            - containerPort: 5432
              name: postgresql
          env:
            - name: POSTGRES_USER
              valueFrom:
                secretKeyRef:
                  name: postgresql-secret
                  key: postgres-user
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgresql-secret
                  key: postgres-password
            - name: POSTGRES_DB
              valueFrom:
                secretKeyRef:
                  name: postgresql-secret
                  key: postgres-db
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
          volumeMounts:
            - name: postgresql-storage
              mountPath: /var/lib/postgresql/data
          resources:
            requests:
              memory: "512Mi"
              cpu: "500m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          livenessProbe:
            tcpSocket:
              port: 5432
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            tcpSocket:
              port: 5432
            initialDelaySeconds: 5
            periodSeconds: 5
      volumes:
        - name: postgresql-storage
          persistentVolumeClaim:
            claimName: postgresql-pvc
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the StatefulSet:

```bash
kubectl apply -f infrastructure/k8s/postgresql/statefulset.yaml
```

### 5. Create PostgreSQL Service

The service manifest file `infrastructure/k8s/postgresql/service.yaml` has already been created with the following content:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: postgresql
  namespace: mbd-infra
spec:
  selector:
    app: postgresql
  ports:
    # Port name must start with 'tcp-' for Istio mTLS to work with PostgreSQL
    # PostgreSQL uses a specific startup protocol before TLS handshake, and Istio's
    # automatic protocol detection needs explicit TCP port naming to avoid ALPN conflicts
    # zie: https://istio.io/latest/docs/ops/configuration/traffic-management/protocol-selection/
    - port: 5432
      targetPort: 5432
      name: tcp-postgresql
  type: ClusterIP
```

**Important Note - Istio mTLS Configuration:**

The PostgreSQL service port is named `tcp-postgresql` (with the `tcp-` prefix) to enable Istio mTLS. This is required because:

- PostgreSQL uses a specific startup protocol before the TLS handshake begins
- Istio's automatic protocol detection can misidentify this as standard TCP traffic, breaking the handshake
- By naming the port with the `tcp-` prefix, we explicitly tell Istio this is a TCP port
- This allows Istio to properly handle the mTLS connection without interfering with PostgreSQL's protocol

Without this configuration, you may encounter connection timeouts or SSL handshake failures when backend services try to connect to PostgreSQL through Istio.

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the service:

```bash
kubectl apply -f infrastructure/k8s/postgresql/service.yaml
```

### 6. Commit PostgreSQL Manifests to GitHub

Add all PostgreSQL manifest files to your GitHub manifests repository:

```bash
# Add files to git
git add infrastructure/k8s/postgresql/storage-class.yaml
git add infrastructure/k8s/postgresql/pvc.yaml
git add infrastructure/k8s/postgresql/statefulset.yaml
git add infrastructure/k8s/postgresql/service.yaml

# Commit and push
git commit -m "Add PostgreSQL manifests for MBD project"
git push origin main
```

### 7. Next Steps

After completing this guide, proceed to:

1. **04-kafka-setup.md** - Deploy Kafka
2. **05-keycloak-setup.md** - Deploy Keycloak
3. **06-argocd-setup.md** - Install ArgoCD and configure GitOps

**Note:** ArgoCD application manifests will be applied in step 6 (06-argocd-setup.md) after all infrastructure is deployed.

### 8. Verify PostgreSQL Deployment

```bash
# Check StatefulSet
kubectl get statefulset postgresql -n mbd-infra

# Check pod
kubectl get pods -n mbd-infra -l app=postgresql

# Check service
kubectl get svc postgresql -n mbd-infra

# Check PVC
kubectl get pvc postgresql-pvc -n mbd-infra

# Check logs
kubectl logs -n mbd-infra -l app=postgresql
```

### 7. Test PostgreSQL Connection

```bash
# Test connection using kubectl exec
kubectl exec -n mbd-infra postgresql-0 -it -- psql -U mbdadmin -d mbd
```

### 8. Database Schema Management

**Note:** Database schema initialization and migrations will be handled by the Spring Boot application using Flyway. The application will automatically apply database migrations on startup based on the migration scripts in the application's resources.

No manual schema initialization is required at this stage. The Spring Boot application will:
- Automatically create the necessary tables on first startup
- Handle schema migrations through Flyway
- Keep the database schema in sync with the application code

## Cleanup

To remove PostgreSQL:

```bash
# Delete via ArgoCD (recommended)
argocd app delete mbd-postgresql

# Or delete directly (not recommended - ArgoCD will recreate)
kubectl delete statefulset postgresql -n mbd-infra
kubectl delete service postgresql -n mbd-infra
kubectl delete pvc postgresql-pvc -n mbd-infra
kubectl delete storageclass postgresql-storage

# Delete secret (not managed by ArgoCD)
kubectl delete secret postgresql-secret -n mbd-infra
```

**Important:** If you delete resources directly, ArgoCD will recreate them on the next sync. To permanently remove, delete the ArgoCD application or remove the manifests from Git.

## Verification

Run these commands to verify the setup:

```bash
# Check PostgreSQL is running
kubectl get pods -n mbd-infra -l app=postgresql

# Check service is accessible
kubectl get svc postgresql -n mbd-infra

# Check PVC is bound
kubectl get pvc postgresql-pvc -n mbd-infra

# Check database tables exist
kubectl exec -n mbd-infra -l app=postgresql -- psql -U mbdadmin -d mbd -c "\dt"

# Check ArgoCD application status
argocd app get mbd-postgresql
```

## ArgoCD Integration

Once the PostgreSQL manifests are in GitHub and the ArgoCD application is configured:

1. ArgoCD will continuously monitor the Git repository
2. Any changes to the PostgreSQL manifests in Git will be automatically synced to the cluster
3. Manual changes via kubectl will be detected as drift and either reverted or flagged
4. All PostgreSQL resources are version-controlled and auditable
5. Secrets are not managed by ArgoCD (use sealed-secrets for production)

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl describe pod -n mbd-infra -l app=postgresql

# Check logs
kubectl logs -n mbd-infra -l app=postgresql

# Check PVC is bound
kubectl get pvc postgresql-pvc -n mbd-infra
```

### Connection Issues

```bash
# Check service endpoints
kubectl get endpoints postgresql -n mbd-infra

# Check if pod is ready
kubectl get pods -n mbd-infra -l app=postgresql

# Test connection from another pod
kubectl run test-pod --image=postgres:15-alpine -n mbd-infra --restart=Never --command -- sleep 3600
kubectl exec -n mbd-infra test-pod -- psql -h postgresql -U mbdadmin -d mbd
kubectl delete pod test-pod -n mbd-infra
```

### Data Persistence Issues

```bash
# Check PVC status
kubectl get pvc postgresql-pvc -n mbd-infra

# Check persistent volume
kubectl get pv

# Check storage class
kubectl get storageclass
```
