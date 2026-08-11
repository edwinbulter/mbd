# Phase 1: Observability with Spring Boot Actuator

This phase focuses on making the internal state and metrics of the backend services visible. By exposing granular health and performance data, the system becomes more maintainable and easier to debug in a cluster environment.

## Goal
To provide production-grade monitoring endpoints for all five microservices, enabling health checks, metrics collection, and future integration with a monitoring stack like Prometheus and Grafana.

## Implementation Details

### 1. Add Dependencies
I will add the following dependencies to the `build.gradle.kts` of `user-service`, `account-service`, `fund-service`, `portfolio-service`, and `admin-service`:
```kotlin
implementation("org.springframework.boot:spring-boot-starter-actuator")
implementation("io.micrometer:micrometer-registry-prometheus")
```

### 2. Configure Endpoints
I will update the `application.yml` files to expose the necessary endpoints:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true # Enables /actuator/health/liveness and /actuator/health/readiness
```

### 3. Kubernetes Integration
I will update the deployment manifests in `infrastructure/k8s/*/deployment.yaml` to point to the standardized actuator health probes:
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
```

## Benefits
- **Proactive Monitoring**: Instead of waiting for users to report errors, we provide the tools to detect issues (e.g., memory leaks, database connection failures) before they impact the user.
- **Infrastructure Alignment**: Using Actuator health groups (`liveness` and `readiness`) is the best practice for Kubernetes orchestration, ensuring pods are only kept in rotation when they are truly ready to serve traffic.
- **Scalability ready**: Exposing metrics in Prometheus format allows for Horizontal Pod Autoscaling (HPA) based on actual application load rather than just CPU usage.
