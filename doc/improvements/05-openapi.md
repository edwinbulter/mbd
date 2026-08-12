# 05 — OpenAPI Documentation

## Overview

All five backend services now expose interactive API documentation through [springdoc-openapi](https://springdoc.org/), which auto-generates an OpenAPI 3 specification from the existing Spring MVC annotations and serves it through Swagger UI.

## Accessing Swagger UI

Each service exposes two endpoints:

| Endpoint | Description |
|----------|-------------|
| `/swagger-ui.html` | Interactive Swagger UI web interface |
| `/v3/api-docs` | Raw OpenAPI 3 JSON spec |

### Through the Istio Gateway

Swagger UI is routed through the Istio ingress gateway for each service. All VirtualService manifests include `/swagger-ui` and `/v3/api-docs` route blocks, and the authorization policy permits these paths without JWT authentication.

Because multiple services share the same gateway hosts (`customer.mbd.local` and `admin.mbd.local`), the Swagger UI path is the same for all services. To view a specific service's docs, use a direct port-forward instead.

### Through port-forward (recommended for local development)

```bash
# User service
kubectl port-forward svc/user-service -n mbd 8080:8080
# Open http://localhost:8080/swagger-ui.html

# Account service
kubectl port-forward svc/account-service -n mbd 8080:8080
# Open http://localhost:8080/swagger-ui.html

# Fund service
kubectl port-forward svc/fund-service -n mbd 8080:8080
# Open http://localhost:8080/swagger-ui.html

# Portfolio service
kubectl port-forward svc/portfolio-service -n mbd 8080:8080
# Open http://localhost:8080/swagger-ui.html

# Admin service
kubectl port-forward svc/admin-service -n mbd 8080:8080
# Open http://localhost:8080/swagger-ui.html
```

## What Swagger UI Shows

The auto-generated documentation includes:

- **All REST endpoints** — HTTP method, path, request parameters, request body schema, and response schemas, derived from `@RestController`, `@GetMapping`, `@PostMapping`, `@PutMapping`, `@DeleteMapping` annotations.
- **DTO schemas** — All request and response data classes from `com.mbd.shared.dto` are automatically documented with their field names and types.
- **Try it out** — Swagger UI allows sending test requests directly from the browser. For admin-service, the `/api/admin/**` endpoints require a valid JWT bearer token; other endpoints are open.

## Configuration

Each service's `application.yml` contains:

```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    operationsSorter: method
```

The `operationsSorter: method` setting groups endpoints by HTTP method (GET, POST, PUT, DELETE) for cleaner presentation.

## Security

- **admin-service**: The `SecurityConfig.kt` permits `/swagger-ui/**`, `/v3/api-docs/**`, and `/swagger-resources/**` without authentication. The `/api/admin/**` endpoints remain protected with the `admin` role via OAuth2 JWT.
- **Other services**: No Spring Security configured, so all endpoints including Swagger UI are open.
- **Istio**: The authorization policy allows `/swagger-ui/*` and `/v3/api-docs/*` without JWT for all services.

## Future Improvements

- **Rich annotations**: Add `@Operation`, `@ApiResponse`, `@Parameter`, and `@Schema` annotations to controllers and DTOs for detailed descriptions, examples, and documented error codes.
- **Spec export**: Add a Gradle task to export OpenAPI JSON files to the repository at build time, enabling version-controlled spec tracking.
- **Frontend code generation**: Generate typed TypeScript API clients from the OpenAPI specs using `openapi-typescript-codegen`, replacing the hand-written `customerApi.ts` and `adminApi.ts` that use `any` types.
- **Backend Feign code generation**: Generate Feign client interfaces from provider service specs using `openapi-generator-gradle-plugin`, replacing the hand-written `FundClient`, `AccountClient`, and `UserClient`.
- **Contract testing**: Use the OpenAPI spec to validate that actual API responses match the documented schema, complementing the existing Testcontainers integration tests.
