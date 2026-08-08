
## Build backend and load image

Optional: Compile locally first to catch errors early
```shell
cd backend
./gradlew :user-service:bootJar
```

Build the Docker image
```shell
cd backend
docker build --no-cache -t user-service:latest -f user-service/Dockerfile .
```

Load the image into the cluster
- **Orbstack**: No loading required if using Orbstack's Docker engine.
- **Kind**: `kind load docker-image user-service:latest --name mbd`

## Build frontend and load image

### Customer Frontend
```shell
cd frontend/customer-frontend
npm install
npm run build
docker build -t customer-frontend:latest .
```

### Admin Frontend
```shell
cd frontend/admin-frontend
npm install
npm run build
docker build -t admin-frontend:latest .
```

Load the images (Kind only)
```shell
kind load docker-image customer-frontend:latest --name mbd
kind load docker-image admin-frontend:latest --name mbd
```

## Accessing Applications (Orbstack)

Update your `/etc/hosts` with the External IP of `istio-ingressgateway`.
- **Keycloak**: http://keycloak.mbd.local/admin
- **Customer Frontend**: http://customer.mbd.local
- **Admin Frontend**: http://admin.mbd.local

## Port forwards (If not using Gateway)
```shell
k port-forward svc/argocd-server -n argocd 8081:443
k port-forward -n mbd-infra kafka-0 9092:9092
# Keycloak direct (bypassing Gateway):
k port-forward -n mbd-infra svc/keycloak 8082:8080
```
