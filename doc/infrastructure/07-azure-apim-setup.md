# Azure APIM Self-Hosted Gateway Setup

This guide explains how to set up Azure API Management (APIM) with a Self-Hosted Gateway to expose MBD backend services.

## Prerequisites

- Azure subscription with APIM enabled
- Azure CLI installed and configured
- Kind cluster running
- mbd namespace created
- Keycloak deployed and configured
- kubectl configured to use the Kind cluster
- Cluster admin permissions

## Important: Azure Resources vs Kubernetes Resources

Azure APIM is a cloud service managed by Azure, not Kubernetes. The APIM instance itself is created and managed via Azure CLI/Terraform. However, the Self-Hosted Gateway component is deployed to Kubernetes and can be managed via ArgoCD manifests. This guide focuses on deploying the Self-Hosted Gateway to Kubernetes using manifests.

## Steps

### 1. Create Azure Resource Group

```bash
# Set your Azure region
AZURE_REGION="westeurope"
RESOURCE_GROUP="mbd-rg"

# Create resource group
az group create \
  --name $RESOURCE_GROUP \
  --location $AZURE_REGION
```

### 2. Create Azure APIM Instance

```bash
# Set APIM configuration
APIM_NAME="mbd-apim"
APIM_SKU="Developer"  # Developer tier for testing, Premium for production
PUBLISHER_NAME="MBD"
PUBLISHER_EMAIL="admin@mbd.local"

# Create APIM instance
az apim create \
  --name $APIM_NAME \
  --resource-group $RESOURCE_GROUP \
  --location $AZURE_REGION \
  --sku-name $APIM_SKU \
  --publisher-name "$PUBLISHER_NAME" \
  --publisher-email "$PUBLISHER_EMAIL"

# Wait for APIM to be created (can take 30-60 minutes)
az apim wait --name $APIM_NAME --resource-group $RESOURCE_GROUP --created
```

### 3. Configure Keycloak OAuth2 in Azure APIM

#### Create Keycloak Identity Provider

```bash
# Get Keycloak URL
KEYCLOAK_URL="http://keycloak.mbd-infra.svc.cluster.local:8080"

# Create OAuth2 identity provider in APIM
az apim identity-provider create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --identity-provider-name keycloak \
  --identity-provider-type openidConnect \
  --signin-tenant "mbd" \
  --client-id "mbd-backend" \
  --client-secret "mbd-backend-secret" \
  --openid-configuration "$KEYCLOAK_URL/realms/mbd/.well-known/openid-configuration"
```

### 4. Create Self-Hosted Gateway

```bash
# Create self-hosted gateway
az apim gateway create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --gateway-name mbd-gateway \
  --description "MBD Self-Hosted Gateway"

# Get gateway token
GATEWAY_TOKEN=$(az apim gateway show \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --gateway-name mbd-gateway \
  --query "gatewayKey" \
  --output tsv)

# Get gateway configuration
az apim gateway show \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --gateway-name mbd-gateway \
  --query "provisioningState"
```

### 5. Create Kubernetes Secret for Gateway Token

Create a secret for the gateway token. **Note:** Secrets should be managed using sealed secrets or external secret management in production. For development, you can create the secret manually:

```bash
# Create secret with gateway token
kubectl create secret generic apim-gateway-token \
  --from-literal=gateway-token=$GATEWAY_TOKEN \
  -n mbd

# Verify secret
kubectl get secret apim-gateway-token -n mbd
```

**Important:** This secret is not managed by ArgoCD. For production, use sealed-secrets or external secret management.

### 6. Deploy Self-Hosted Gateway to Kubernetes

The deployment manifest file `infrastructure/k8s/apim/gateway-deployment.yaml` has already been created with the following content:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: apim-gateway
  namespace: mbd
spec:
  replicas: 1
  selector:
    matchLabels:
      app: apim-gateway
  template:
    metadata:
      labels:
        app: apim-gateway
    spec:
      containers:
        - name: apim-gateway
          image: mcr.microsoft.com/azure-api-management/gateway:latest
          env:
            - name: config.service.endpoint
              value: "https://$APIM_NAME.management.azure-api.net"
            - name: config.service.auth
              valueFrom:
                secretKeyRef:
                  name: apim-gateway-token
                  key: gateway-token
            - name: net.dns.refresh
              value: "30"
            - name: net.dns.ttl
              value: "30"
          ports:
            - containerPort: 8080
              name: http
            - containerPort: 8443
              name: https
          resources:
            requests:
              memory: "512Mi"
              cpu: "500m"
            limits:
              memory: "1Gi"
              cpu: "1000m"
          livenessProbe:
            httpGet:
              path: /status-0123456789abcdef
              port: 3939
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /status-0123456789abcdef
              port: 3939
            initialDelaySeconds: 10
            periodSeconds: 5
```

**Important:** This file must be committed to your GitHub repository in the manifests repository. Replace `$APIM_NAME` with your actual APIM name before committing.

Apply the deployment:

```bash
# Replace $APIM_NAME with actual value
sed "s/\$APIM_NAME/$APIM_NAME/g" infrastructure/k8s/apim/gateway-deployment.yaml | kubectl apply -f -
```

### 7. Create Gateway Service

The service manifest file `infrastructure/k8s/apim/gateway-service.yaml` has already been created with the following content:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: apim-gateway
  namespace: mbd
spec:
  selector:
    app: apim-gateway
  ports:
    - port: 80
      targetPort: 8080
      name: http
    - port: 443
      targetPort: 8443
      name: https
  type: ClusterIP
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the service:

```bash
kubectl apply -f infrastructure/k8s/apim/gateway-service.yaml
```

### 8. Create Istio VirtualService for Gateway

The VirtualService manifest file `infrastructure/k8s/apim/virtualservice.yaml` has already been created with the following content:

```yaml
apiVersion: networking.istio.io/v1beta1
kind: VirtualService
metadata:
  name: apim-gateway
  namespace: mbd
spec:
  hosts:
    - "*"
  gateways:
    - mbd/mbd-gateway
  http:
    - match:
        - uri:
            prefix: /api
      route:
        - destination:
            host: apim-gateway
            port:
              number: 80
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the VirtualService:

```bash
kubectl apply -f infrastructure/k8s/apim/virtualservice.yaml
```

### 9. Commit APIM Gateway Manifests to GitHub

Add all APIM gateway manifest files to your GitHub manifests repository:

```bash
# Add files to git
git add infrastructure/k8s/apim/gateway-deployment.yaml
git add infrastructure/k8s/apim/gateway-service.yaml
git add infrastructure/k8s/apim/virtualservice.yaml

# Commit and push
git commit -m "Add Azure APIM Self-Hosted Gateway manifests for MBD project"
git push origin main
```

### 10. Configure ArgoCD Application (After ArgoCD Installation)

**Important:** ArgoCD must be installed before you can apply ArgoCD application manifests. Follow the ArgoCD setup guide in `06-argocd-setup.md` first.

The ArgoCD application manifest file `infrastructure/argocd/apim-app.yaml` has already been created with the following content:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: mbd-apim-gateway
  namespace: argocd
spec:
  project: default
  source:
    repoURL: git@github.com:edwinbulter/mbd.git
    targetRevision: main
    path: infrastructure/k8s/apim
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

Apply the ArgoCD application (after ArgoCD is installed):

```bash
kubectl apply -f infrastructure/argocd/apim-app.yaml
```

### 11. Define Backend APIs in Azure APIM

#### Create User Service API

```bash
# Create API for user service
az apim api create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id user-service \
  --path "/api/users" \
  --display-name "User Service" \
  --protocols http https \
  --service-url "http://user-service.mbd.svc.cluster.local:8080"
```

#### Create Account Service API

```bash
az apim api create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id account-service \
  --path "/api/accounts" \
  --display-name "Account Service" \
  --protocols http https \
  --service-url "http://account-service.mbd.svc.cluster.local:8080"
```

#### Create Fund Service API

```bash
az apim api create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id fund-service \
  --path "/api/funds" \
  --display-name "Fund Service" \
  --protocols http https \
  --service-url "http://fund-service.mbd.svc.cluster.local:8080"
```

#### Create Portfolio Service API

```bash
az apim api create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id portfolio-service \
  --path "/api/portfolio" \
  --display-name "Portfolio Service" \
  --protocols http https \
  --service-url "http://portfolio-service.mbd.svc.cluster.local:8080"
```

#### Create Admin Service API

```bash
az apim api create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id admin-service \
  --path "/api/admin" \
  --display-name "Admin Service" \
  --protocols http https \
  --service-url "http://admin-service.mbd.svc.cluster.local:8080"
```

### 12. Configure API Operations

#### Add Operations to User Service

```bash
# Register user
az apim operation create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id user-service \
  --operation-id register \
  --url-template "/register" \
  --method "POST" \
  --display-name "Register User"

# Get user profile
az apim operation create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id user-service \
  --operation-id get-profile \
  --url-template "/profile" \
  --method "GET" \
  --display-name "Get User Profile"
```

#### Add Operations to Account Service

```bash
# Create account
az apim operation create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id account-service \
  --operation-id create-account \
  --url-template "/accounts" \
  --method "POST" \
  --display-name "Create Account"

# Deposit money
az apim operation create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id account-service \
  --operation-id deposit \
  --url-template "/accounts/{accountId}/deposit" \
  --method "POST" \
  --display-name "Deposit Money"
```

### 13. Configure OAuth2 Policy

Create an inbound policy to validate JWT tokens:

```xml
<!-- infrastructure/k8s/apim/policies/oauth2-policy.xml -->
<inbound>
    <base />
    <validate-jwt header-name="Authorization" failed-validation-httpcode="401" failed-validation-error-message="Unauthorized. Access token is missing or invalid.">
        <openid-config url="http://keycloak.mbd-infra.svc.cluster.local:8080/realms/mbd/.well-known/openid-configuration" />
        <required-claims>
            <claim name="aud" match="any">
                <value>mbd-backend</value>
            </claim>
        </required-claims>
    </validate-jwt>
</inbound>
```

Apply the policy to all APIs:

```bash
# Apply policy to user service
az apim policy create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id user-service \
  --policy-format xml \
  --policy-file infrastructure/k8s/apim/policies/oauth2-policy.xml

# Apply to other services similarly
az apim policy create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id account-service \
  --policy-format xml \
  --policy-file infrastructure/k8s/apim/policies/oauth2-policy.xml
```

### 14. Configure CORS Policy

Create a CORS policy for frontend access:

```xml
<!-- infrastructure/k8s/apim/policies/cors-policy.xml -->
<inbound>
    <base />
    <cors>
        <allowed-origins>
            <origin>http://localhost:3000</origin>
            <origin>http://localhost:3001</origin>
        </allowed-origins>
        <allowed-methods>
            <method>GET</method>
            <method>POST</method>
            <method>PUT</method>
            <method>DELETE</method>
            <method>OPTIONS</method>
        </allowed-methods>
        <allowed-headers>
            <header>*</header>
        </allowed-headers>
        <expose-headers>
            <header>*</header>
        </expose-headers>
        <max-age>3600</max-age>
    </cors>
</inbound>
```

Apply the CORS policy:

```bash
az apim policy create \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id user-service \
  --policy-format xml \
  --policy-file infrastructure/k8s/apim/policies/cors-policy.xml
```

### 15. Verify Gateway Deployment

```bash
# Check gateway pod
kubectl get pods -n mbd -l app=apim-gateway

# Check gateway service
kubectl get svc apim-gateway -n mbd

# Check gateway logs
kubectl logs -n mbd -l app=apim-gateway

# Check gateway status in Azure
az apim gateway show \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --gateway-name mbd-gateway
```

### 16. Test API Access

```bash
# Port forward to access gateway
kubectl port-forward -n mbd svc/apim-gateway 8080:80

# Test API endpoint (with valid JWT token)
curl -H "Authorization: Bearer <your-jwt-token>" \
  http://localhost:8080/api/users/profile
```

## Cleanup

To remove Azure APIM resources:

```bash
# Delete via ArgoCD (recommended)
argocd app delete mbd-apim-gateway

# Or delete directly (not recommended - ArgoCD will recreate)
kubectl delete deployment apim-gateway -n mbd
kubectl delete service apim-gateway -n mbd
kubectl delete virtualservice apim-gateway -n mbd

# Delete secret (not managed by ArgoCD)
kubectl delete secret apim-gateway-token -n mbd

# Delete gateway from Azure
az apim gateway delete \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --gateway-name mbd-gateway

# Delete APIM instance
az apim delete \
  --name $APIM_NAME \
  --resource-group $RESOURCE_GROUP

# Delete resource group
az group delete \
  --name $RESOURCE_GROUP
```

**Important:** If you delete Kubernetes resources directly, ArgoCD will recreate them on the next sync. To permanently remove, delete the ArgoCD application or remove the manifests from Git.

## Verification

Run these commands to verify the setup:

```bash
# Check Azure resources
az apim list --resource-group $RESOURCE_GROUP
az apim gateway list --resource-group $RESOURCE_GROUP --name $APIM_NAME

# Check Kubernetes resources
kubectl get pods -n mbd -l app=apim-gateway
kubectl get svc apim-gateway -n mbd

# Check API definitions
az apim api list --resource-group $RESOURCE_GROUP --name $APIM_NAME

# Test API access
kubectl port-forward -n mbd svc/apim-gateway 8080:80
curl http://localhost:8080/api/users/profile

# Check ArgoCD application status
argocd app get mbd-apim-gateway
```

## ArgoCD Integration

Once the APIM gateway manifests are in GitHub and the ArgoCD application is configured:

1. ArgoCD will continuously monitor the Git repository
2. Any changes to the APIM gateway manifests in Git will be automatically synced to the cluster
3. Manual changes via kubectl will be detected as drift and either reverted or flagged
4. All APIM gateway resources are version-controlled and auditable
5. Secrets are not managed by ArgoCD (use sealed-secrets for production)
6. Azure APIM instance and API definitions are managed via Azure CLI, not ArgoCD

## Troubleshooting

### Gateway Pod Not Starting

```bash
# Check pod status
kubectl describe pod -n mbd -l app=apim-gateway

# Check logs
kubectl logs -n mbd-gateway -l app=apim-gateway

# Check secret exists
kubectl get secret apim-gateway-token -n mbd

# Verify gateway token
kubectl get secret apim-gateway-token -n mbd -o jsonpath='{.data.gateway-token}' | base64 -d
```

### Gateway Not Connecting to Azure

```bash
# Check gateway status in Azure
az apim gateway show \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --gateway-name mbd-gateway

# Check network connectivity
kubectl exec -n mbd -l app=apim-gateway -- nslookup $APIM_NAME.management.azure-api.net

# Check gateway configuration
kubectl exec -n mbd -l app=apim-gateway -- env | grep config
```

### API Not Accessible

```bash
# Check API configuration
az apim api show \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id user-service

# Check backend service is accessible
kubectl exec -n mbd -l app=apim-gateway -- curl http://user-service.mbd.svc.cluster.local:8080/profile

# Check Istio routing
istioctl proxy-config routes -n mbd $(kubectl get pod -n mbd -l app=apim-gateway -o jsonpath='{.items[0].metadata.name}')
```

### OAuth2 Validation Failing

```bash
# Check Keycloak is accessible
kubectl exec -n mbd -l app=apim-gateway -- curl http://keycloak.mbd-infra.svc.cluster.local:8080/realms/mbd/.well-known/openid-configuration

# Check policy configuration
az apim policy show \
  --resource-group $RESOURCE_GROUP \
  --name $APIM_NAME \
  --api-id user-service

# Test with valid token
# Get token from Keycloak and test API call
```

## Security Considerations

- Use Premium tier for production with proper SLA
- Enable Azure Firewall or Network Security Groups
- Use managed identities for Azure resources
- Implement proper IP restrictions
- Enable Azure Monitor and logging
- Use Azure Key Vault for secrets
- Implement proper rate limiting
- Enable API versioning
- Use SSL/TLS for all communications
- Regularly rotate gateway tokens
- Implement proper backup and disaster recovery

## Cost Optimization

- Use Developer tier for development/testing
- Monitor API usage and optimize
- Use auto-scaling for gateway instances
- Implement caching policies
- Use Azure Cost Management tools
- Review and optimize API policies
- Monitor gateway resource usage
