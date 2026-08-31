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
    "serviceAccountsEnabled": true
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

# Create admin role
curl -s -X POST "http://localhost:8082/admin/realms/mbd/roles" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "admin",
    "description": "Administrator role"
  }'

# Create admin user (admin-1)
ADMIN_USER_RESPONSE=$(curl -s -X POST "http://localhost:8082/admin/realms/mbd/users" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "admin-1",
    "email": "admin-1@mbd.local",
    "firstName": "Admin",
    "lastName": "One",
    "enabled": true,
    "emailVerified": true,
    "credentials": [{
      "type": "password",
      "value": "Hello-admin-1",
      "temporary": false
    }]
  }')

# Get admin user ID
ADMIN_USER_ID=$(curl -s "http://localhost:8082/admin/realms/mbd/users?username=admin-1" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" | jq -r '.[0].id')

ADMIN_KEYCLOAK_ID=$(curl -s "http://localhost:8082/admin/realms/mbd/users/$ADMIN_USER_ID" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" | jq -r '.id')

# Assign admin role to admin-1
ADMIN_ROLE_ID=$(curl -s "http://localhost:8082/admin/realms/mbd/roles/admin" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" | jq -r '.id')

curl -s -X POST "http://localhost:8082/admin/realms/mbd/users/$ADMIN_USER_ID/role-mappings/realm" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d "[{\"id\": \"$ADMIN_ROLE_ID\", \"name\": \"admin\"}]"

# Create customer user (user-1)
CUSTOMER_USER_RESPONSE=$(curl -s -X POST "http://localhost:8082/admin/realms/mbd/users" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "user-1",
    "email": "user-1@mbd.local",
    "firstName": "User",
    "lastName": "One",
    "enabled": true,
    "emailVerified": true,
    "credentials": [{
      "type": "password",
      "value": "Hello-user-1",
      "temporary": false
    }]
  }')

# Get customer user ID
CUSTOMER_USER_ID=$(curl -s "http://localhost:8082/admin/realms/mbd/users?username=user-1" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" | jq -r '.[0].id')

CUSTOMER_KEYCLOAK_ID=$(curl -s "http://localhost:8082/admin/realms/mbd/users/$CUSTOMER_USER_ID" \
  -H "Authorization: Bearer $KEYCLOAK_TOKEN" | jq -r '.id')

# Register users in MBD database via user-service API
echo "Registering users in MBD database..."

# Port forward to user-service
kubectl port-forward -n mbd svc/user-service 8083:8080 > /dev/null 2>&1 &
USER_SVC_PF_PID=$!
sleep 3

# Register admin user in MBD
curl -s -X POST "http://localhost:8083/api/users/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"keycloakId\": \"$ADMIN_KEYCLOAK_ID\",
    \"email\": \"admin-1@mbd.local\",
    \"firstName\": \"Admin\",
    \"lastName\": \"One\",
    \"role\": \"admin\"
  }"

# Register customer user in MBD
curl -s -X POST "http://localhost:8083/api/users/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"keycloakId\": \"$CUSTOMER_KEYCLOAK_ID\",
    \"email\": \"user-1@mbd.local\",
    \"firstName\": \"User\",
    \"lastName\": \"One\",
    \"role\": \"user\"
  }"

# Kill user-service port forward
kill $USER_SVC_PF_PID

echo "Keycloak realm configured successfully"

# Kill port forward
kill $PF_PID
