# Optional: Compile locally first to catch errors early
cd backend && ./gradlew :user-service:bootJar

# Build the Docker image
cd backend && docker build --no-cache -t user-service:latest -f user-service/Dockerfile .

# Load the image into the Kind cluster
kind load docker-image user-service:latest --name multi-node-cluster

# Delete the existing deployment (if it exists)
kubectl delete deployment user-service -n mbd

# Apply the deployment manifest
kubectl apply -f infrastructure/k8s/user-service/deployment.yaml

# Scale the deployment to 1 replica
kubectl scale deployment user-service --replicas=1 -n mbd

# Check the pod status
kubectl get pods -n mbd

# View the logs to verify it started successfully
kubectl logs -f deployment/user-service -n mbd