# Backend Testing Guide

This guide describes how to test the `mbd` backend services locally and inside the Kind Kubernetes cluster.

## Architecture Overview

The backend consists of five Spring Boot + Kotlin microservices:

- `user-service`
- `account-service`
- `fund-service`
- `portfolio-service`
- `admin-service`

All services share a single PostgreSQL database (`mbd`) in the `mbd-infra` namespace. Each service has its own Flyway schema history table. Kafka is available for event-driven features and Istio provides mTLS between services.

---

## 1. Compile and Unit Tests

Run the full Gradle test suite from the `backend` directory:

```bash
cd backend
./gradlew test
```

Run tests for a single service:

```bash
./gradlew :user-service:test
./gradlew :account-service:test
```

Build all service JARs:

```bash
./gradlew build
```

If Docker builds pick up stale compiled resources, clean first:

```bash
rm -rf */build */bin
./gradlew build
```

---

## 2. Integration Tests with Testcontainers

The services are configured for JPA repositories and can be tested with an embedded or Testcontainer database. To run integration tests that require PostgreSQL:

1. Ensure Docker is running.
2. Add a Testcontainers configuration in `src/test/resources/application.yml` for the service under test.
3. Run:

```bash
./gradlew :<service>:test
```

A minimal test `application.yml`:

```yaml
spring:
  datasource:
    url: jdbc:tc:postgresql:15:///mbd
    username: mbdadmin
    password: mbdadmin
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: true
  flyway:
    enabled: true
    baseline-on-migrate: true
    baseline-version: 0
```

> Note: `jdbc:tc:postgresql:15:///` is the Testcontainers JDBC URL scheme. The actual image tag may need to match the version used in the cluster.

---

## 3. Database Migration Validation

Validate Flyway migrations for one service:

```bash
./gradlew :<service>:bootRun
```

or run Flyway directly from the packaged JAR in a container:

```bash
docker run --rm -it fund-service:latest \
  java -jar app.jar \
  --spring.flyway.validate-on-migrate=true \
  --spring.datasource.url=jdbc:postgresql://<host>:5432/mbd
```

To inspect Flyway history in the shared database:

```bash
kubectl exec -it postgresql-0 -n mbd-infra -- \
  psql -U mbdadmin -d mbd -c "SELECT * FROM <service>_flyway_history;"
```

For example:

```bash
kubectl exec -it postgresql-0 -n mbd-infra -- \
  psql -U mbdadmin -d mbd -c "SELECT * FROM user_service_flyway_history;"
```

---

## 4. Local API Testing with curl

Port-forward a service to your machine:

```bash
kubectl port-forward svc/user-service 8080:8080 -n mbd
```

Then test endpoints:

### user-service

```bash
# Create a user
curl -X POST http://localhost:8080/api/users \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"secret","firstName":"Test","lastName":"User"}'

# List users
curl http://localhost:8080/api/users
```

### account-service

```bash
# Create an account for a user (depends on user-service tables)
curl -X POST http://localhost:8080/api/accounts \
  -H "Content-Type: application/json" \
  -d '{"userId":1,"accountNumber":"NL00MBD0000000001","accountType":"SAVINGS","currency":"EUR"}'

# List accounts
curl http://localhost:8080/api/accounts
```

### fund-service

```bash
# Create a fund
curl -X POST http://localhost:8080/api/funds \
  -H "Content-Type: application/json" \
  -d '{"name":"Global Fund","isin":"NL0000000001","currentPrice":100.00,"currency":"EUR","volatility":0.02,"updateFrequencyMinutes":5}'

# List funds
curl http://localhost:8080/api/funds
```

### portfolio-service

```bash
# Create a holding (depends on account and fund tables)
curl -X POST http://localhost:8080/api/holdings \
  -H "Content-Type: application/json" \
  -d '{"accountId":1,"fundId":1,"quantity":10,"purchasePrice":100.00}'

# List holdings
curl http://localhost:8080/api/holdings
```

### admin-service

```bash
# Create a config value
curl -X POST http://localhost:8080/api/admin/config \
  -H "Content-Type: application/json" \
  -d '{"key":"maintenance_mode","value":"false","description":"Enable maintenance mode"}'

# Get a config value
curl http://localhost:8080/api/admin/config/maintenance_mode
```

---

## 5. End-to-End Testing in the Kind Cluster

### 5.1 Verify all pods are healthy

```bash
kubectl get pods -n mbd
kubectl get pods -n mbd-infra
```

Every application pod should show `2/2` READY (application container + Istio sidecar).

### 5.2 Check service logs

```bash
kubectl logs -n mbd -l app=<service-name> --tail=50
```

For example:

```bash
kubectl logs -n mbd -l app=fund-service --tail=50
```

### 5.3 Test via the Istio Ingress Gateway

Get the gateway port:

```bash
kubectl get svc istio-ingressgateway -n istio-system
```

With LoadBalancer support, access a service by its VirtualService host:

```bash
curl -H "Host: user-service.mbd.local" \
  http://<ingress-ip>/api/users
```

Without a real LoadBalancer, use the NodePort or port-forward the gateway:

```bash
kubectl port-forward svc/istio-ingressgateway 8080:80 -n istio-system
```

```bash
curl -H "Host: user-service.mbd.local" http://localhost:8080/api/users
```

### 5.4 Verify mTLS

Confirm the `mbd-infra` namespace has Istio injection enabled:

```bash
kubectl get ns mbd-infra --show-labels
```

Check that both application and sidecar containers are running:

```bash
kubectl get pods -n mbd -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.status.containerStatuses[*].name}{"\n"}{end}'
```

---

## 6. Kafka Testing

List available Kafka topics:

```bash
kubectl exec -it kafka-0 -n mbd-infra -- \
  /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka.mbd-infra.svc.cluster.local:9092 --list
```

Produce a test message:

```bash
kubectl exec -it kafka-0 -n mbd-infra -- \
  /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server kafka.mbd-infra.svc.cluster.local:9092 \
  --topic test-topic
```

Consume from a topic:

```bash
kubectl exec -it kafka-0 -n mbd-infra -- \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server kafka.mbd-infra.svc.cluster.local:9092 \
  --topic test-topic --from-beginning
```

---

## 7. Database State Inspection

Open a PostgreSQL shell:

```bash
kubectl exec -it postgresql-0 -n mbd-infra -- psql -U mbdadmin -d mbd
```

Useful commands inside `psql`:

```sql
-- List all tables
\dt

-- Describe a table
\d users
\d accounts
\d funds
\d holdings
\d transactions
\d system_config

-- List all Flyway history tables
SELECT table_name FROM information_schema.tables WHERE table_name LIKE '%_flyway_history';
```

---

## 8. Smoke Test Checklist

Use this checklist after every deployment to the Kind cluster:

- [ ] All pods in `mbd` are `2/2` with `0` restarts.
- [ ] PostgreSQL pod in `mbd-infra` is `2/2` (Istio sidecar injected).
- [ ] Each Flyway history table has a baseline and `success = t` for V1.
- [ ] `user-service` responds to `POST /api/users` and `GET /api/users`.
- [ ] `account-service` can create an account for an existing user.
- [ ] `fund-service` can create a fund without serial/integer column errors.
- [ ] `portfolio-service` can create a holding linked to an account and fund.
- [ ] `admin-service` can read and write `system_config` values.
- [ ] Kafka topics can be listed and messages produced/consumed.

---

## 9. Common Test Failures

| Symptom | Cause | Fix |
|---------|-------|-----|
| `wrong column type encountered ... found [serial (Types#INTEGER)], but expecting [bigint (Types#BIGINT)]` | Migration uses `SERIAL` instead of `BIGSERIAL`. | Update `V1__*.sql` to use `BIGSERIAL` and rebuild the image. |
| `FlywayValidateException: Migration checksum mismatch` | Migration file changed after it was applied. | Drop the service table and its Flyway history, then redeploy. |
| `Unable to determine SQL type name for column 'volatility'` | `Double` field annotated with `precision`/`scale`. | Remove `precision`/`scale` for floating point fields or use `BigDecimal`. |
| `PSQLException: Connection refused` or `SocketTimeoutException` | Istio sidecar not ready or NetworkPolicy blocks traffic. | Add `holdApplicationUntilProxyStarts` and verify namespace labels in NetworkPolicy. |
| `Found non-empty schema ... no schema history table` | Existing tables without Flyway baseline. | Add `baseline-on-migrate: true` and `baseline-version: 0` to `application.yml`. |
