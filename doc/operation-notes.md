
## Build and load image

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

Load the image into the Kind cluster (Not necessary if you use Orbstack K8s)
```shell
kind load docker-image user-service:latest --name multi-node-cluster
```

## Port forwards
```shell
k port-forward svc/argocd-server -n argocd 8081:443
k port-forward -n mbd-infra kafka-0 9092:9092
k port-forward -n mbd-infra svc/keycloak 8082:8080
```
