
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

Load the images (for Kind cluster only, not necessary for Orbstack cluster)
```shell
kind load docker-image customer-frontend:latest --name mbd
kind load docker-image admin-frontend:latest --name mbd
```

## Accessing Applications in Orbstack cluster
Extend your `/etc/hosts` with `127.0.0.1 customer.mbd.local admin.mbd.local keycloak.mbd.local kafbat.mbd.local`
- **Customer Frontend**: http://customer.mbd.local
- **Admin Frontend**: http://admin.mbd.local


## Creating an admin account
1. Create a normal user account through the regular registration flow (e.g., the customer frontend).
2. Open the [Keycloak admin console](http://keycloak.mbd.local/admin), log in, and locate the newly created user. Assign the `admin` **realm role** via **Role mapping**.
3. Log out of the MBD admin frontend and log back in with the same user, so the new role is included in the access token.


## Swagger UI
```shell
kubectl port-forward svc/fund-service -n mbd 9080:8080
# Open http://localhost:9080/swagger-ui.html

# You can use this also for: portfolio-service, user-service, account-service, admin-service
```

## ArgoCD UI
```shell
kubectl port-forward svc/argocd-server -n argocd 8081:443
# Open https://localhost:8081
```

## Other UIs
- https://kafbat.mbd.local
- https://keycloak.mbd.local


## Run unit tests
```shell
cd backend
./gradlew test --tests "com.mbd.portfolio.service.*" --tests "com.mbd.fund.*" --tests "com.mbd.account.controller.*" --tests "com.mbd.user.controller.UserControllerTest" --tests "com.mbd.admin.controller.*" --no-daemon
```
