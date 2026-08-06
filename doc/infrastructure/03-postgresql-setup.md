# PostgreSQL Setup

This guide explains how to deploy PostgreSQL in the mbd-infra namespace with persistent storage using manifests that will be managed by ArgoCD.

## Prerequisites

- Kind cluster running
- mbd-infra namespace created
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
            exec:
              command:
                - pg_isready
                - -U
                - $(POSTGRES_USER)
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            exec:
              command:
                - pg_isready
                - -U
                - $(POSTGRES_USER)
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
    - port: 5432
      targetPort: 5432
      name: postgresql
  type: ClusterIP
```

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

### 7. Configure ArgoCD Application

Create an ArgoCD application to manage PostgreSQL resources:

```yaml
# infrastructure/argocd/postgresql-app.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: mbd-postgresql
  namespace: argocd
spec:
  project: default
  source:
    repoURL: git@github.com:your-username/mbd-manifests.git
    targetRevision: main
    path: infrastructure/k8s/postgresql
  destination:
    server: https://kubernetes.default.svc
    namespace: mbd-infra
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

Apply the ArgoCD application:

```bash
kubectl apply -f infrastructure/argocd/postgresql-app.yaml
```

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
# Port forward to access PostgreSQL locally
kubectl port-forward -n mbd-infra svc/postgresql 5432:5432

# In another terminal, test connection
PGPASSWORD=$(kubectl get secret postgresql-secret -n mbd-infra -o jsonpath='{.data.postgres-password}' | base64 -d)
PGUSER=$(kubectl get secret postgresql-secret -n mbd-infra -o jsonpath='{.data.postgres-user}' | base64 -d)
PGDATABASE=$(kubectl get secret postgresql-secret -n mbd-infra -o jsonpath='{.data.postgres-db}' | base64 -d)

psql -h localhost -p 5432 -U $PGUSER -d $PGDATABASE
```

### 8. Initialize Database Schema

Create an initialization script:

```sql
-- infrastructure/k8s/postgresql/init-schema.sql
-- Users table
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    keycloak_id VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Accounts table
CREATE TABLE IF NOT EXISTS accounts (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id),
    account_number VARCHAR(50) UNIQUE NOT NULL,
    balance DECIMAL(15, 2) DEFAULT 0.00,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Funds table
CREATE TABLE IF NOT EXISTS funds (
    id SERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    isin VARCHAR(50) UNIQUE NOT NULL,
    current_price DECIMAL(15, 4) NOT NULL,
    currency VARCHAR(3) DEFAULT 'EUR',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Holdings table
CREATE TABLE IF NOT EXISTS holdings (
    id SERIAL PRIMARY KEY,
    account_id INTEGER REFERENCES accounts(id),
    fund_id INTEGER REFERENCES funds(id),
    quantity DECIMAL(15, 4) NOT NULL,
    average_price DECIMAL(15, 4) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(account_id, fund_id)
);

-- Transactions table
CREATE TABLE IF NOT EXISTS transactions (
    id SERIAL PRIMARY KEY,
    account_id INTEGER REFERENCES accounts(id),
    type VARCHAR(50) NOT NULL, -- DEPOSIT, WITHDRAWAL, BUY, SELL
    amount DECIMAL(15, 2) NOT NULL,
    fund_id INTEGER REFERENCES funds(id),
    quantity DECIMAL(15, 4),
    price_per_unit DECIMAL(15, 4),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Portfolio snapshots table
CREATE TABLE IF NOT EXISTS portfolio_snapshots (
    id SERIAL PRIMARY KEY,
    account_id INTEGER REFERENCES accounts(id),
    total_value DECIMAL(15, 2) NOT NULL,
    snapshot_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- System config table
CREATE TABLE IF NOT EXISTS system_config (
    id SERIAL PRIMARY KEY,
    key VARCHAR(255) UNIQUE NOT NULL,
    value TEXT NOT NULL,
    description TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Insert default system config
INSERT INTO system_config (key, value, description) VALUES
    ('price_update_frequency_minutes', '5', 'Frequency of fund price updates in minutes'),
    ('price_update_volatility_percent', '2', 'Volatility percentage for random price changes')
ON CONFLICT (key) DO NOTHING;
```

Apply the schema:

```bash
# Copy the schema to the pod
kubectl cp infrastructure/k8s/postgresql/init-schema.sql \
  $(kubectl get pod -n mbd-infra -l app=postgresql -o jsonpath='{.items[0].metadata.name}'):/tmp/init-schema.sql \
  -n mbd-infra

# Execute the schema
kubectl exec -n mbd-infra -l app=postgresql -- psql -U mbdadmin -d mbd -f /tmp/init-schema.sql
```

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
