#!/bin/bash

# Define namespaces
INFRA_NS="mbd-infra"
APP_NS="mbd"

# Create namespaces if they don't exist
kubectl create namespace $INFRA_NS --dry-run=client -o yaml | kubectl apply -f -
kubectl create namespace $APP_NS --dry-run=client -o yaml | kubectl apply -f -

# 1. Create PostgreSQL Secret in mbd-infra
echo "Creating postgresql-secret in $INFRA_NS..."
kubectl create secret generic postgresql-secret \
  --from-literal=postgres-password=mbdpassword \
  --from-literal=postgres-user=mbdadmin \
  --from-literal=postgres-db=mbd \
  -n $INFRA_NS --dry-run=client -o yaml | kubectl apply -f -

# 2. Create PostgreSQL Secret in mbd (for backend services)
echo "Creating postgresql-secret in $APP_NS..."
kubectl create secret generic postgresql-secret \
  --from-literal=postgres-password=mbdpassword \
  --from-literal=postgres-user=mbdadmin \
  --from-literal=postgres-db=mbd \
  -n $APP_NS --dry-run=client -o yaml | kubectl apply -f -

# 3. Create Keycloak Secret in mbd-infra
echo "Creating keycloak-secret in $INFRA_NS..."
kubectl create secret generic keycloak-secret \
  --from-literal=admin-password=admin \
  --from-literal=db-password=keycloakpassword \
  -n $INFRA_NS --dry-run=client -o yaml | kubectl apply -f -

echo "All development secrets created successfully!"
