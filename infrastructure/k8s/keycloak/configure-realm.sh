#!/bin/bash

# Get Keycloak admin password
KEYCLOAK_ADMIN_PASSWORD=$(kubectl get secret keycloak-secret -n mbd-infra -o jsonpath='{.data.admin-password}' | base64 -d)

# Wait for Keycloak to be ready
echo "Waiting for Keycloak to be ready..."
kubectl wait --for=condition=available deployment/keycloak -n mbd-infra --timeout=300s

# Port forward
kubectl port-forward -n mbd-infra svc/keycloak 8082:8080 &
PF_PID=$!
sleep 10

# Login to Keycloak admin
KEYCLOAK_TOKEN=$(curl -s -X POST "http://localhost:8082/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "username=admin" \
  -d "password=$KEYCLOAK_ADMIN_PASSWORD" \
  -d "grant_type=password" \
  -d "client_id=admin-cli" | jq -r '.access_token')

# Create MBD realm
curl -s -X POST "http://localhost:8082/admin/realms" \
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

# Create customer role
curl -s -X POST "http://localhost:8082/admin/realms/mbd/roles" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "customer",
    "description": "Regular customer role"
  }'

# Create admin role
curl -s -X POST "http://localhost:8082/admin/realms/mbd/roles" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "admin",
    "description": "Bank administrator role"
  }'

# Create confidential client for backend services
curl -s -X POST "http://localhost:8082/admin/realms/mbd/clients" \
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

# Create customer-frontend public client
curl -s -X POST "http://localhost:8082/admin/realms/mbd/clients" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "customer-frontend",
    "enabled": true,
    "publicClient": true,
    "redirectUris": ["https://customer.mbd.local/*"],
    "webOrigins": ["https://customer.mbd.local"],
    "standardFlowEnabled": true,
    "directAccessGrantsEnabled": true
  }'

# Create admin-frontend public client
curl -s -X POST "http://localhost:8082/admin/realms/mbd/clients" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "admin-frontend",
    "enabled": true,
    "publicClient": true,
    "redirectUris": ["https://admin.mbd.local/*"],
    "webOrigins": ["https://admin.mbd.local"],
    "standardFlowEnabled": true,
    "directAccessGrantsEnabled": true
  }'

# Add realm roles mapper to all clients via client scope
# Get the 'roles' client scope ID
CLIENT_SCOPE_ID=$(curl -s "http://localhost:8082/admin/realms/mbd/client-scopes" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" | jq -r '.[] | select(.name=="roles") | .id')

# Add protocol mapper for realm roles to top-level 'roles' claim
curl -s -X POST "http://localhost:8082/admin/realms/mbd/client-scopes/$CLIENT_SCOPE_ID/protocol-mappers/models" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "realm roles mapper",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-usermodel-realm-role-mapper",
    "config": {
      "claim.name": "roles",
      "jsonType.label": "String",
      "multivalued": "true",
      "userinfo.token.claim": "true",
      "id.token.claim": "true",
      "access.token.claim": "true"
    }
  }'

echo "Keycloak realm configured successfully"

# Kill port forward
kill $PF_PID
