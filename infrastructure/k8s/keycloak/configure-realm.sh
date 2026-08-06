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

# Create user role
curl -s -X POST "http://localhost:8082/admin/realms/mbd/roles" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "user",
    "description": "Regular user role"
  }'

# Create employee role
curl -s -X POST "http://localhost:8082/admin/realms/mbd/roles" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "employee",
    "description": "Bank employee role"
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

# Create public client for frontend
curl -s -X POST "http://localhost:8082/admin/realms/mbd/clients" \
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
