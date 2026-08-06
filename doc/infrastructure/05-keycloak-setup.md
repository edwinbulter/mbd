# Keycloak Setup

This guide explains how to deploy Keycloak for the MBD project.

## Prerequisites

- Kind cluster running
- mbd-infra namespace created (from 01-namespace-setup.md)
- Istio installed (from 02-istio-setup.md)
- PostgreSQL deployed (from 03-postgresql-setup.md)
- Kafka deployed (from 04-kafka-setup.md)
- kubectl configured to use the Kind cluster
- Cluster admin permissions

## Important: ArgoCD Management

**All Kubernetes resources should be managed through manifests in your GitHub repository, not through kubectl commands.** This ensures ArgoCD can track and manage all resources without drift. Secrets should be managed separately using sealed secrets or external secret management.

## Steps

### 1. Create Storage Class for Keycloak

The storage class manifest file `infrastructure/k8s/keycloak/storage-class.yaml` has already been created with the following content:

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: keycloak-storage
provisioner: rancher.io/local-path
reclaimPolicy: Retain
volumeBindingMode: WaitForFirstConsumer
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the storage class:

```bash
kubectl apply -f infrastructure/k8s/keycloak/storage-class.yaml
```

### 2. Create Keycloak Secret

Create a secret for Keycloak credentials. **Note:** Secrets should be managed using sealed secrets or external secret management in production. For development, you can create the secret manually:

```bash
# Generate strong passwords
KEYCLOAK_ADMIN_PASSWORD=$(openssl rand -base64 32)
KEYCLOAK_DB_PASSWORD=$(openssl rand -base64 32)

# Create the secret
kubectl create secret generic keycloak-secret \
  --from-literal=admin-password=$KEYCLOAK_ADMIN_PASSWORD \
  --from-literal=db-password=$KEYCLOAK_DB_PASSWORD \
  -n mbd-infra

# Save passwords for reference
echo "Keycloak Admin Password: $KEYCLOAK_ADMIN_PASSWORD" > /tmp/keycloak-passwords.txt
echo "Keycloak DB Password: $KEYCLOAK_DB_PASSWORD" >> /tmp/keycloak-passwords.txt

# Verify the secret
kubectl get secret keycloak-secret -n mbd-infra
```

**Important:** This secret is not managed by ArgoCD. For production, use sealed-secrets or external secret management.

### 3. Create Persistent Volume Claim for Keycloak

The PVC manifest file `infrastructure/k8s/keycloak/pvc.yaml` has already been created with the following content:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: keycloak-pvc
  namespace: mbd-infra
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: keycloak-storage
  resources:
    requests:
      storage: 2Gi
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the PVC:

```bash
kubectl apply -f infrastructure/k8s/keycloak/pvc.yaml
```

### 4. Deploy PostgreSQL for Keycloak

The PostgreSQL StatefulSet manifest file `infrastructure/k8s/keycloak/postgresql-statefulset.yaml` has already been created with the following content:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: keycloak-postgresql
  namespace: mbd-infra
spec:
  serviceName: keycloak-postgresql
  replicas: 1
  selector:
    matchLabels:
      app: keycloak-postgresql
  template:
    metadata:
      labels:
        app: keycloak-postgresql
    spec:
      containers:
        - name: postgresql
          image: postgres:15-alpine
          ports:
            - containerPort: 5432
              name: postgresql
          env:
            - name: POSTGRES_USER
              value: keycloak
            - name: POSTGRES_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: keycloak-secret
                  key: db-password
            - name: POSTGRES_DB
              value: keycloak
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
          volumeMounts:
            - name: postgresql-storage
              mountPath: /var/lib/postgresql/data
          resources:
            requests:
              memory: "256Mi"
              cpu: "250m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          livenessProbe:
            exec:
              command:
                - pg_isready
                - -U
                - keycloak
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            exec:
              command:
                - pg_isready
                - -U
                - keycloak
            initialDelaySeconds: 5
            periodSeconds: 5
      volumes:
        - name: postgresql-storage
          persistentVolumeClaim:
            claimName: keycloak-pvc
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the StatefulSet:

```bash
kubectl apply -f infrastructure/k8s/keycloak/postgresql-statefulset.yaml
```

### 5. Create PostgreSQL Service for Keycloak

The service manifest file `infrastructure/k8s/keycloak/postgresql-service.yaml` has already been created with the following content:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: keycloak-postgresql
  namespace: mbd-infra
spec:
  selector:
    app: keycloak-postgresql
  ports:
    - port: 5432
      targetPort: 5432
      name: postgresql
  type: ClusterIP
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the service:

```bash
kubectl apply -f infrastructure/k8s/keycloak/postgresql-service.yaml
```

### 6. Deploy Keycloak

The deployment manifest file `infrastructure/k8s/keycloak/deployment.yaml` has already been created with the following content:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: keycloak
  namespace: mbd-infra
spec:
  replicas: 1
  selector:
    matchLabels:
      app: keycloak
  template:
    metadata:
      labels:
        app: keycloak
    spec:
      containers:
        - name: keycloak
          image: quay.io/keycloak/keycloak:23.0
          args:
            - start
          ports:
            - containerPort: 8080
              name: http
          env:
            - name: KEYCLOAK_ADMIN
              value: admin
            - name: KEYCLOAK_ADMIN_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: keycloak-secret
                  key: admin-password
            - name: KC_DB
              value: postgres
            - name: KC_DB_URL
              value: jdbc:postgresql://keycloak-postgresql:5432/keycloak
            - name: KC_DB_USERNAME
              value: keycloak
            - name: KC_DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: keycloak-secret
                  key: db-password
            - name: KC_HOSTNAME
              value: keycloak.mbd-infra.svc.cluster.local
            - name: KC_HOSTNAME_STRICT
              value: "false"
            - name: KC_HOSTNAME_STRICT_HTTPS
              value: "false"
            - name: KC_HTTP_ENABLED
              value: "true"
            - name: KC_PROXY
              value: edge
          resources:
            requests:
              memory: "512Mi"
              cpu: "500m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          livenessProbe:
            httpGet:
              path: /health/live
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /health/ready
              port: 8080
            initialDelaySeconds: 30
            periodSeconds: 5
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the deployment:

```bash
kubectl apply -f infrastructure/k8s/keycloak/deployment.yaml
```

### 7. Create Keycloak Service

The service manifest file `infrastructure/k8s/keycloak/service.yaml` has already been created with the following content:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: keycloak
  namespace: mbd-infra
spec:
  selector:
    app: keycloak
  ports:
    - port: 8080
      targetPort: 8080
      name: http
  type: ClusterIP
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the service:

```bash
kubectl apply -f infrastructure/k8s/keycloak/service.yaml
```

### 8. Create Istio VirtualService for Keycloak

The VirtualService manifest file `infrastructure/k8s/keycloak/virtualservice.yaml` has already been created with the following content:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: keycloak
  namespace: mbd-infra
spec:
  hosts:
    - "*"
  gateways:
    - mbd/mbd-gateway
  http:
    - match:
        - uri:
            prefix: /auth
      route:
        - destination:
            host: keycloak
            port:
              number: 8080
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the VirtualService:

```bash
kubectl apply -f infrastructure/k8s/keycloak/virtualservice.yaml
```

### 9. Commit Keycloak Manifests to GitHub

Add all Keycloak manifest files to your GitHub manifests repository:

```bash
# Add files to git
git add infrastructure/k8s/keycloak/storage-class.yaml
git add infrastructure/k8s/keycloak/pvc.yaml
git add infrastructure/k8s/keycloak/postgresql-statefulset.yaml
git add infrastructure/k8s/keycloak/postgresql-service.yaml
git add infrastructure/k8s/keycloak/deployment.yaml
git add infrastructure/k8s/keycloak/service.yaml
git add infrastructure/k8s/keycloak/virtualservice.yaml

# Commit and push
git commit -m "Add Keycloak manifests for MBD project"
git push origin main
```

### 10. Next Steps

After completing this guide, proceed to:

1. **06-argocd-setup.md** - Install ArgoCD and configure GitOps

**Note:** ArgoCD application manifests will be applied in step 6 (06-argocd-setup.md) after all infrastructure is deployed.

### 11. Verify Keycloak Deployment

```bash
# Check deployment
kubectl get deployment keycloak -n mbd-infra

# Check pod
kubectl get pods -n mbd-infra -l app=keycloak

# Check service
kubectl get svc keycloak -n mbd-infra

# Check PostgreSQL
kubectl get pods -n mbd-infra -l app=keycloak-postgresql

# Check logs
kubectl logs -n mbd-infra -l app=keycloak
```

### 10. Access Keycloak Admin Console

```bash
# Port forward to access Keycloak locally
kubectl port-forward -n mbd-infra svc/keycloak 8080:8080

# Access the admin console at: http://localhost:8080/admin
# Username: admin
# Password: Check /tmp/keycloak-passwords.txt
```

### 11. Configure Keycloak Realm

Create a script to configure Keycloak:

```bash
# infrastructure/k8s/keycloak/configure-realm.sh
#!/bin/bash

# Get Keycloak admin password
KEYCLOAK_ADMIN_PASSWORD=$(kubectl get secret keycloak-secret -n mbd-infra -o jsonpath='{.data.admin-password}' | base64 -d)

# Wait for Keycloak to be ready
echo "Waiting for Keycloak to be ready..."
kubectl wait --for=condition=available deployment/keycloak -n mbd-infra --timeout=300s

# Port forward
kubectl port-forward -n mbd-infra svc/keycloak 8080:8080 &
PF_PID=$!
sleep 10

# Login to Keycloak admin
KEYCLOAK_TOKEN=$(curl -s -X POST "http://localhost:8080/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin" \
  -d "password=$KEYCLOAK_ADMIN_PASSWORD" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" | jq -r '.access_token')

# Create MBD realm
curl -s -X POST "http://localhost:8080/admin/realms" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "realm": "mbd",
    "enabled": true,
    "sslRequired": "external",
    "registrationAllowed": true,
    "loginWithEmailAllowed": true,
    "duplicateEmailsAllowed": false,
    "resetPasswordAllowed": true,
    "editUsernameAllowed": false,
    "bruteForceProtected": true
  }'

# Create user role
curl -s -X POST "http://localhost:8080/admin/realms/mbd/roles" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "user",
    "description": "Regular user role"
  }'

# Create employee role
curl -s -X POST "http://localhost:8080/admin/realms/mbd/roles" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "employee",
    "description": "Bank employee role"
  }'

# Create confidential client for backend services
curl -s -X POST "http://localhost:8080/admin/realms/mbd/clients" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "mbd-backend",
    "enabled": true,
    "clientAuthenticatorType": "client-secret",
    "secret": "mbd-backend-secret",
    "redirectUris": ["http://localhost:*/*"],
    "webOrigins": ["http://localhost:*"],
    "standardFlowEnabled": true,
    "directAccessGrantsEnabled": true,
    "serviceAccountsEnabled": true,
    "validRedirectUris": ["http://localhost:*/*"]
  }'

# Create public client for frontend
curl -s -X POST "http://localhost:8080/admin/realms/mbd/clients" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "mbd-frontend",
    "enabled": true,
    "publicClient": true,
    "redirectUris": ["http://localhost:*/*", "http://localhost:*"],
    "webOrigins": ["http://localhost:*"],
    "standardFlowEnabled": true,
    "directAccessGrantsEnabled": true
  }'

echo "Keycloak realm configured successfully"

# Kill port forward
kill $PF_PID
```

Make the script executable and run it:

```bash
chmod +x infrastructure/k8s/keycloak/configure-realm.sh
./infrastructure/k8s/keycloak/configure-realm.sh
```

### 12. Verify Keycloak Configuration

Access the Keycloak admin console at http://localhost:8080/admin and verify:
- Realm "mbd" exists
- Roles "user" and "employee" exist
- Clients "mbd-backend" and "mbd-frontend" exist

## Cleanup

To remove Keycloak:

```bash
# Delete via ArgoCD (recommended)
argocd app delete mbd-keycloak

# Or delete directly (not recommended - ArgoCD will recreate)
kubectl delete deployment keycloak -n mbd-infra
kubectl delete service keycloak -n mbd-infra
kubectl delete statefulset keycloak-postgresql -n mbd-infra
kubectl delete service keycloak-postgresql -n mbd-infra
kubectl delete pvc keycloak-pvc -n mbd-infra
kubectl delete virtualservice keycloak -n mbd-infra
kubectl delete storageclass keycloak-storage

# Delete secret (not managed by ArgoCD)
kubectl delete secret keycloak-secret -n mbd-infra
```

**Important:** If you delete resources directly, ArgoCD will recreate them on the next sync. To permanently remove, delete the ArgoCD application or remove the manifests from Git.

## Verification

Run these commands to verify the setup:

```bash
# Check Keycloak is running
kubectl get pods -n mbd-infra -l app=keycloak

# Check PostgreSQL is running
kubectl get pods -n mbd-infra -l app=keycloak-postgresql

# Check services
kubectl get svc -n mbd-infra | grep keycloak

# Check VirtualService
kubectl get virtualservice keycloak -n mbd-infra

# Access admin console
kubectl port-forward -n mbd-infra svc/keycloak 8080:8080
# Open http://localhost:8080/admin

# Check ArgoCD application status
argocd app get mbd-keycloak
```

## ArgoCD Integration

Once the Keycloak manifests are in GitHub and the ArgoCD application is configured:

1. ArgoCD will continuously monitor the Git repository
2. Any changes to the Keycloak manifests in Git will be automatically synced to the cluster
3. Manual changes via kubectl will be detected as drift and either reverted or flagged
4. All Keycloak resources are version-controlled and auditable
5. Secrets are not managed by ArgoCD (use sealed-secrets for production)
6. Keycloak realm configuration is done via script (not managed by ArgoCD)

## Troubleshooting

### Keycloak Pod Not Starting

```bash
# Check pod status
kubectl describe pod -n mbd-infra -l app=keycloak

# Check logs
kubectl logs -n mbd-infra -l app=keycloak

# Check PostgreSQL is ready
kubectl get pods -n mbd-infra -l app=keycloak-postgresql

# Check database connection
kubectl exec -n mbd-infra keycloak-postgresql-0 -- psql -U keycloak -d keycloak -c "SELECT 1"
```

### Database Connection Issues

```bash
# Check PostgreSQL service
kubectl get svc keycloak-postgresql -n mbd-infra

# Check PostgreSQL endpoints
kubectl get endpoints keycloak-postgresql -n mbd-infra

# Test connection from Keycloak pod
kubectl exec -n mbd-infra -l app=keycloak -- nc -zv keycloak-postgresql 5432
```

### Realm Configuration Issues

```bash
# Check if Keycloak is ready
kubectl exec -n mbd-infra -l app=keycloak -- curl -s http://localhost:8080/health/ready

# Check realm exists
kubectl port-forward -n mbd-infra svc/keycloak 8080:8080
curl -s http://localhost:8080/realms/mbd

# Check client configuration
# Access admin console and verify clients
```

### Access Issues

```bash
# Check VirtualService
kubectl get virtualservice keycloak -n mbd-infra

# Check Gateway
kubectl get gateway -n mbd

# Check Istio routes
istioctl proxy-config routes -n mbd-infra $(kubectl get pod -n mbd-infra -l app=keycloak -o jsonpath='{.items[0].metadata.name}')
```

## Security Notes

- Change default admin password in production
- Enable HTTPS for production deployments
- Use proper SSL certificates
- Configure proper CORS settings
- Enable brute force protection (already enabled in realm config)
- Regularly update Keycloak to latest version
- Backup Keycloak database regularly
