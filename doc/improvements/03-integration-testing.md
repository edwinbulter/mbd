# Phase 3: Integration Testing with Testcontainers

This phase focuses on high-quality, reliable code validation. Unit tests are often insufficient for a microservices architecture that relies on external systems like Kafka and PostgreSQL.

## Goal
To implement true integration tests that run against real, ephemeral instances of Kafka and PostgreSQL using Testcontainers, ensuring the system behaves correctly in a near-production environment.

## Implementation Details

### 1. Test Infrastructure
I will add the Testcontainers BOM and dependencies to the backend projects:
- `org.testcontainers:postgresql`
- `org.testcontainers:kafka`

### 2. Portfolio Service End-to-End Test
The most complex flow in MBD is the async price update. I will create a `PortfolioIntegrationTest` that:
1. Starts a Kafka container and a PostgreSQL container.
2. Injects a `FundPriceUpdate` into the Kafka topic.
3. Verifies that the `FundPriceUpdateConsumer` picks it up.
4. Asserts that the corresponding `Holding` in the database has a new `currentValue`.
5. Asserts that a new `PortfolioValueSnapshot` has been created.

### 3. Database Migration Test
I will add a test in `user-service` that simply starts a PostgreSQL container and lets Flyway run. This ensures that the SQL migration scripts are valid and can actually be applied to a clean database.

## Isolation and Safety
A critical advantage of using Testcontainers is the total isolation between the test environment and the running application. The tests will **not** affect the database or Kafka instances in your OrbStack cluster:

- **Ephemeral Containers**: Testcontainers spins up brand-new, empty instances of PostgreSQL and Kafka as Docker containers. These exist only for the duration of the test and are deleted immediately after.
- **Random Dynamic Ports**: Instead of using default ports (like 5432 or 9092), Testcontainers maps the container ports to random available high ports on your host machine. This prevents collisions with your running services.
- **Dynamic Configuration**: Using Spring Boot 3.1's `@ServiceConnection`, the application context is automatically injected with the temporary connection details (localhost + random port). The test "thinks" it's talking to the cluster, but it's actually interacting with a private, temporary instance.

## Benefits
- **Infrastructure-as-Code Validation**: We are testing that our code correctly interacts with the real infrastructure it will use in production.
- **Flaky-free Tests**: Unlike "mocked" tests, Testcontainers tests provide high confidence. Unlike "shared dev DB" tests, they are isolated and won't fail because of data left over from another test run.
- **Asynchronous Reliability**: Testing Kafka consumers is often complex; a working integration test suite provides the necessary safety net for event-driven logic.
