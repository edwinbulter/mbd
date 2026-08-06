# Kafka Setup (KRaft Mode)

This guide explains how to deploy Apache Kafka in KRaft mode (without Zookeeper) in the mbd-infra namespace using manifests that will be managed by ArgoCD.

## Prerequisites

- Kind cluster running
- mbd-infra namespace created
- kubectl configured to use the Kind cluster
- Cluster admin permissions

## Important: ArgoCD Management

**All Kubernetes resources should be managed through manifests in your GitHub repository, not through kubectl commands.** This ensures ArgoCD can track and manage all resources without drift.

## Steps

### 1. Create Storage Class for Kafka

The storage class manifest file `infrastructure/k8s/kafka/storage-class.yaml` has already been created with the following content:

```yaml
apiVersion: storage.k8s.io/v1
kind: StorageClass
metadata:
  name: kafka-storage
provisioner: rancher.io/local-path
reclaimPolicy: Retain
volumeBindingMode: WaitForFirstConsumer
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the storage class:

```bash
kubectl apply -f infrastructure/k8s/kafka/storage-class.yaml
```

### 2. Create Persistent Volume Claim for Kafka

The PVC manifest file `infrastructure/k8s/kafka/pvc.yaml` has already been created with the following content:

```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: kafka-pvc
  namespace: mbd-infra
spec:
  accessModes:
    - ReadWriteOnce
  storageClassName: kafka-storage
  resources:
    requests:
      storage: 5Gi
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the PVC:

```bash
kubectl apply -f infrastructure/k8s/kafka/pvc.yaml
```

### 3. Create Kafka ConfigMap

The ConfigMap manifest file `infrastructure/k8s/kafka/configmap.yaml` has already been created with the following content:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: kafka-config
  namespace: mbd-infra
data:
  KAFKA_BROKER_ID: "1"
  KAFKA_NODE_ID: "1"
  KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka-0.kafka.mbd-infra.svc.cluster.local:9093"
  KAFKA_LISTENERS: "PLAINTEXT://:9092,BROKER://:9093"
  KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://kafka.mbd-infra.svc.cluster.local:9092,BROKER://kafka-0.kafka.mbd-infra.svc.cluster.local:9093"
  KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "BROKER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
  KAFKA_INTER_BROKER_LISTENER_NAME: "BROKER"
  KAFKA_CONTROLLER_LISTENER_NAMES: "BROKER"
  KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: "1"
  KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: "1"
  KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: "1"
  KAFKA_LOG_DIRS: "/var/lib/kafka/data"
  KAFKA_PROCESS_ROLES: "broker,controller"
  KAFKA_LOG_RETENTION_HOURS: "168"
  KAFKA_LOG_SEGMENT_BYTES: "1073741824"
  KAFKA_LOG_RETENTION_CHECK_INTERVAL_MS: "300000"
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the ConfigMap:

```bash
kubectl apply -f infrastructure/k8s/kafka/configmap.yaml
```

### 4. Deploy Kafka StatefulSet

The StatefulSet manifest file `infrastructure/k8s/kafka/statefulset.yaml` has already been created with the following content:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: kafka
  namespace: mbd-infra
spec:
  serviceName: kafka
  replicas: 1
  selector:
    matchLabels:
      app: kafka
  template:
    metadata:
      labels:
        app: kafka
    spec:
      containers:
        - name: kafka
          image: confluentinc/cp-kafka:7.5.0
          ports:
            - containerPort: 9092
              name: plaintext
            - containerPort: 9093
              name: broker
          envFrom:
            - configMapRef:
                name: kafka-config
          env:
            - name: KAFKA_CLUSTER_ID
              value: "MBD-Kafka-Cluster-001"
          volumeMounts:
            - name: kafka-storage
              mountPath: /var/lib/kafka/data
          resources:
            requests:
              memory: "1Gi"
              cpu: "500m"
            limits:
              memory: "2Gi"
              cpu: "1000m"
          livenessProbe:
            tcpSocket:
              port: 9092
            initialDelaySeconds: 30
            periodSeconds: 10
          readinessProbe:
            tcpSocket:
              port: 9092
            initialDelaySeconds: 10
            periodSeconds: 5
      volumes:
        - name: kafka-storage
          persistentVolumeClaim:
            claimName: kafka-pvc
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the StatefulSet:

```bash
kubectl apply -f infrastructure/k8s/kafka/statefulset.yaml
```

### 5. Create Kafka Service

The service manifest file `infrastructure/k8s/kafka/service.yaml` has already been created with the following content:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: kafka
  namespace: mbd-infra
spec:
  selector:
    app: kafka
  ports:
    - port: 9092
      targetPort: 9092
      name: plaintext
    - port: 9093
      targetPort: 9093
      name: broker
  type: ClusterIP
  clusterIP: None
```

**Important:** This file must be committed to your GitHub repository in the manifests repository.

Apply the service:

```bash
kubectl apply -f infrastructure/k8s/kafka/service.yaml
```

### 6. Commit Kafka Manifests to GitHub

Add all Kafka manifest files to your GitHub manifests repository:

```bash
# Add files to git
git add infrastructure/k8s/kafka/storage-class.yaml
git add infrastructure/k8s/kafka/pvc.yaml
git add infrastructure/k8s/kafka/configmap.yaml
git add infrastructure/k8s/kafka/statefulset.yaml
git add infrastructure/k8s/kafka/service.yaml

# Commit and push
git commit -m "Add Kafka manifests for MBD project"
git push origin main
```

### 7. Configure ArgoCD Application

Create an ArgoCD application to manage Kafka resources:

```yaml
# infrastructure/argocd/kafka-app.yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: mbd-kafka
  namespace: argocd
spec:
  project: default
  source:
    repoURL: git@github.com:your-username/mbd-manifests.git
    targetRevision: main
    path: infrastructure/k8s/kafka
  destination:
    server: https://kubernetes.default.svc
    namespace: mbd-infra
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
    syncOptions:
      - CreateNamespace=true
```

Apply the ArgoCD application:

```bash
kubectl apply -f infrastructure/argocd/kafka-app.yaml
```

### 8. Verify Kafka Deployment

```bash
# Check StatefulSet
kubectl get statefulset kafka -n mbd-infra

# Check pod
kubectl get pods -n mbd-infra -l app=kafka

# Check service
kubectl get svc kafka -n mbd-infra

# Check PVC
kubectl get pvc kafka-pvc -n mbd-infra

# Check logs
kubectl logs -n mbd-infra -l app=kafka
```

### 7. Create Kafka Topics

Create a script to initialize Kafka topics:

```bash
# infrastructure/k8s/kafka/create-topics.sh
#!/bin/bash

# Wait for Kafka to be ready
echo "Waiting for Kafka to be ready..."
kubectl wait --for=condition=ready pod -l app=kafka -n mbd-infra --timeout=300s

# Create topics
echo "Creating Kafka topics..."

# Fund price updates topic
kubectl exec -n mbd-infra kafka-0 -- kafka-topics --create \
  --if-not-exists \
  --bootstrap-server localhost:9092 \
  --topic fund-price-updates \
  --partitions 3 \
  --replication-factor 1

# Portfolio updates topic
kubectl exec -n mbd-infra kafka-0 -- kafka-topics --create \
  --if-not-exists \
  --bootstrap-server localhost:9092 \
  --topic portfolio-updates \
  --partitions 3 \
  --replication-factor 1

echo "Kafka topics created successfully"

# List topics
kubectl exec -n mbd-infra kafka-0 -- kafka-topics --list --bootstrap-server localhost:9092
```

Make the script executable and run it:

```bash
chmod +x infrastructure/k8s/kafka/create-topics.sh
./infrastructure/k8s/kafka/create-topics.sh
```

### 8. Test Kafka

```bash
# Port forward to access Kafka locally
kubectl port-forward -n mbd-infra kafka-0 9092:9092

# In another terminal, produce a test message
kubectl exec -n mbd-infra kafka-0 -- kafka-console-producer \
  --bootstrap-server localhost:9092 \
  --topic fund-price-updates

# Type a message and press Enter
{"fundId": 1, "price": 100.50, "timestamp": "2024-01-01T00:00:00Z"}

# Press Ctrl+C to exit

# Consume the message
kubectl exec -n mbd-infra kafka-0 -- kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic fund-price-updates \
  --from-beginning

# Press Ctrl+C to exit
```

## Cleanup

To remove Kafka:

```bash
# Delete via ArgoCD (recommended)
argocd app delete mbd-kafka

# Or delete directly (not recommended - ArgoCD will recreate)
kubectl delete statefulset kafka -n mbd-infra
kubectl delete service kafka -n mbd-infra
kubectl delete pvc kafka-pvc -n mbd-infra
kubectl delete configmap kafka-config -n mbd-infra
kubectl delete storageclass kafka-storage
```

**Important:** If you delete resources directly, ArgoCD will recreate them on the next sync. To permanently remove, delete the ArgoCD application or remove the manifests from Git.

## Verification

Run these commands to verify the setup:

```bash
# Check Kafka is running
kubectl get pods -n mbd-infra -l app=kafka

# Check service is accessible
kubectl get svc kafka -n mbd-infra

# Check PVC is bound
kubectl get pvc kafka-pvc -n mbd-infra

# Check topics exist
kubectl exec -n mbd-infra kafka-0 -- kafka-topics --list --bootstrap-server localhost:9092

# Check cluster ID
kubectl exec -n mbd-infra kafka-0 -- kafka-metadata-quorum --bootstrap-server localhost:9092 describe --status

# Check ArgoCD application status
argocd app get mbd-kafka
```

## ArgoCD Integration

Once the Kafka manifests are in GitHub and the ArgoCD application is configured:

1. ArgoCD will continuously monitor the Git repository
2. Any changes to the Kafka manifests in Git will be automatically synced to the cluster
3. Manual changes via kubectl will be detected as drift and either reverted or flagged
4. All Kafka resources are version-controlled and auditable
5. Kafka topics are not managed by ArgoCD (created via script)

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl describe pod -n mbd-infra -l app=kafka

# Check logs
kubectl logs -n mbd-infra -l app=kafka

# Check PVC is bound
kubectl get pvc kafka-pvc -n mbd-infra

# Check ConfigMap
kubectl get configmap kafka-config -n mbd-infra
```

### KRaft Mode Issues

```bash
# Check cluster ID
kubectl exec -n mbd-infra kafka-0 -- env | grep KAFKA_CLUSTER_ID

# Check KRaft metadata
kubectl exec -n mbd-infra kafka-0 -- kafka-metadata-quorum --bootstrap-server localhost:9092 describe --status

# Check controller quorum
kubectl exec -n mbd-infra kafka-0 -- kafka-metadata-quorum --bootstrap-server localhost:9092 describe --replica
```

### Connection Issues

```bash
# Check service endpoints
kubectl get endpoints kafka -n mbd-infra

# Check if pod is ready
kubectl get pods -n mbd-infra -l app=kafka

# Test connection from another pod
kubectl run test-kafka --image=confluentinc/cp-kafka:7.5.0 -n mbd-infra --restart=Never --command -- sleep 3600
kubectl exec -n mbd-infra test-kafka -- kafka-broker-api-versions --bootstrap-server kafka.mbd-infra.svc.cluster.local:9092
kubectl delete pod test-kafka -n mbd-infra
```

### Topic Creation Issues

```bash
# Check if Kafka is ready
kubectl exec -n mbd-infra kafka-0 -- kafka-broker-api-versions --bootstrap-server localhost:9092

# List existing topics
kubectl exec -n mbd-infra kafka-0 -- kafka-topics --list --bootstrap-server localhost:9092

# Describe a topic
kubectl exec -n mbd-infra kafka-0 -- kafka-topics --describe --topic fund-price-updates --bootstrap-server localhost:9092
```

## Scaling Kafka

For production, you may want to scale Kafka to multiple brokers:

```yaml
# Update the StatefulSet replicas
spec:
  replicas: 3

# Update the ConfigMap with additional broker IDs
KAFKA_CONTROLLER_QUORUM_VOTERS: "1@kafka-0.kafka.mbd-infra.svc.cluster.local:9093,2@kafka-1.kafka.mbd-infra.svc.cluster.local:9093,3@kafka-2.kafka.mbd-infra.svc.cluster.local:9093"
```

Note: Scaling requires careful planning and should be done during maintenance windows.
