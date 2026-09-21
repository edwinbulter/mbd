# Fix Feasibility Analysis: Issue #2 — No Authentication Implemented

**Date**: 2026-09-20
**Source finding**: `Three-Way-Security-Tool-Comparison.md`, "Issue #2: No Authentication (CRITICAL)"
**Question answered**: Can this be fixed in the current codebase, and how?

---

## Verdict

**Yes — fixable, and the fix pattern already exists in this codebase.** `admin-service` implements exactly the Spring Security OAuth2 Resource Server setup that `portfolio-service` needs; the same dependency, config class, and `application.yml` block can be reused almost verbatim. This is a config + one small `@Configuration` class change, not a redesign.

It's worth being precise about what's actually missing, though: the mesh is *not* doing nothing today. Istio already validates JWTs for traffic entering the mesh from outside. The real gap is narrower and more interesting than "zero authentication anywhere" — see §2.

---

## 1. Confirming the current state

`portfolio-service/build.gradle.kts` has no `spring-boot-starter-security` or `spring-boot-starter-oauth2-resource-server` dependency at all — only `spring-boot-starter-web`. `PortfolioController` has no `@PreAuthorize`, no `Authentication`/`Jwt` parameter, nothing:

```kotlin
@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(private val portfolioService: PortfolioService) {
    @GetMapping("/{accountId}")
    fun getPortfolio(@PathVariable accountId: Long): ResponseEntity<PortfolioDto> { ... }

    @PostMapping("/trade")
    fun executeTrade(@RequestBody trade: TradeDto): ResponseEntity<HoldingDto> { ... }
    ...
}
```

Anything that can reach the pod on port 8080 and knows an account ID can call these endpoints. The finding is accurate: the *application* has no authentication whatsoever.

---

## 2. What actually protects portfolio-service today (and what doesn't)

This matters for picking the right fix, so it's worth mapping precisely:

| Layer | What it checks | Covers external traffic? | Covers pod-to-pod traffic inside `mbd`? |
|---|---|---|---|
| `PeerAuthentication` (STRICT mTLS, `mbd` namespace) | Caller has a valid Istio-issued workload certificate | ✅ (enforced at the gateway's mTLS termination) | ✅ — but this proves *which pod* is calling, not *which user* |
| `RequestAuthentication` (`jwt-authn`) | If a JWT is present, it must be valid against Keycloak's JWKS | ✅ | ⚠️ only if a JWT is attached |
| `AuthorizationPolicy` (`api-access-policy`) | Grants access to `/api/*` | — | **Rule 1 allows any pod with `principal: cluster.local/ns/mbd/*` — no JWT required at all.** Rule 2 separately allows any request with a *valid* JWT (any authenticated user, any role). |
| Application (`PortfolioController`) | Nothing | ❌ | ❌ |

The consequence of `api-access-policy`'s first rule: **any pod inside the `mbd` namespace — including `customer-frontend`, `admin-frontend`, or any pod compromised by an unrelated bug (e.g. the Kafka RCE in Issue #1) — can call `portfolio-service`'s API with no JWT and no user context whatsoever**, because mesh identity (an mTLS cert every pod in the namespace gets) is treated as sufficient. This is the real hole: it's not that *nothing* checks tokens, it's that the mesh's own policy has an unauthenticated bypass for same-namespace traffic, and the application has nothing behind it to catch what the mesh lets through.

Practical implication: any exploit that gets code execution on *any* workload in `mbd` (compromised frontend dependency, the Kafka deserialization bug, etc.) gets unauthenticated read/write access to every account's portfolio and can execute trades on any account. Fixing this only at the mesh level (tightening `api-access-policy`) is fragile and duplicated across services; fixing it in the application is the standard defense-in-depth answer, and it's what `admin-service` already does for its own endpoints.

---

## 3. The fix: replicate `admin-service`'s pattern

### 3a. Add the dependency

`portfolio-service/build.gradle.kts`:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")           // add
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // add
    ...
}
```

These are the exact two lines already present in `admin-service/build.gradle.kts:25-26`.

### 3b. Add issuer/JWKS config

`portfolio-service/src/main/resources/application.yml`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://keycloak.mbd.local/realms/mbd
          jwk-set-uri: http://keycloak.mbd-infra.svc.cluster.local:8080/realms/mbd/protocol/openid-connect/certs
```

Identical to `admin-service/application.yml:21-26` — same realm, same JWKS endpoint, no new infrastructure required.

### 3c. Add a `SecurityConfig`

`portfolio-service/src/main/kotlin/com/mbd/portfolio/config/SecurityConfig.kt` (new file):

```kotlin
package com.mbd.portfolio.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests { authz ->
                authz
                    .requestMatchers("/actuator/health/**").permitAll()   // kubelet probes carry no JWT
                    .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { it.jwt { } }
        return http.build()
    }
}
```

This is deliberately narrower than `admin-service`'s `permitAll("/actuator/**")`: only `/actuator/health/**` is opened, since the readiness/liveness/startup probes in `infrastructure/k8s/portfolio-service/deployment.yaml` only hit `/actuator/health/liveness` and `/actuator/health/readiness`. Blanket-exposing `/actuator/**` (including `/metrics`, `/prometheus`, `/env`) is Issue #5 in the same report and shouldn't be reintroduced while fixing Issue #2.

### Why this is safe to add — traced, not assumed

- **The frontend already sends a Bearer token on every request.** Per the architecture, `customerApi.ts`'s Axios interceptor attaches the Keycloak access token to every call, including the portfolio endpoints. No frontend change is needed — it's already producing exactly what a resource server needs.
- **No backend service calls `portfolio-service`'s HTTP API.** A repo-wide search for other services referencing `portfolio-service` turns up only the shared `PortfolioDto` (a DTO import, not an HTTP caller). `portfolio-service` itself is a Feign *client* to `account-service` and `fund-service`, never a Feign *server* target for another backend service. So there is no service-to-service caller that would need a service-account token minted for it — every inbound HTTP request is either an end-user request (has a JWT) or a kubelet probe (permitted above).
- **Existing tests don't break.** `PortfolioIntegrationTest` is the only Spring context test in the service; it drives the Kafka consumer and repositories directly and never issues an HTTP call to the controller, so it's unaffected by adding a security filter chain. There is currently no `@WebMvcTest`/`MockMvc` controller test for `PortfolioController` to update.

### What this does *not* yet fix

Requiring "a valid JWT" answers **authentication** ("is this a real, logged-in user?") but not **authorization** ("does this user own account 42?"). `getPortfolio(accountId)`, `getPortfolioHistory(accountId)`, and `executeTrade(trade)` still don't check the caller's identity against the account being accessed — that's Issue #3 in the same report (horizontal privilege escalation) and requires comparing the JWT's `sub`/account mapping against the requested `accountId`, which is a separate, slightly larger change (needs a way to resolve `keycloak sub → owned account id`, likely via a call to `account-service`/`user-service`). Fixing #2 is a prerequisite for #3 — you need a validated identity in hand before you can check what it's allowed to touch — but #2 alone doesn't close #3.

### Consider fixing `user-service`'s manual JWT handling at the same time

`UserController.extractKeycloakIdFromToken` manually splits the JWT and Base64-decodes the payload **without verifying the signature**:

```kotlin
private fun extractKeycloakIdFromToken(authHeader: String): String {
    val token = authHeader.removePrefix("Bearer ")
    val parts = token.split(".")
    val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]))
    return jacksonObjectMapper().readTree(payload).get("sub").asText()
}
```

This isn't the pattern to copy into `portfolio-service` — it works today only because Istio's `RequestAuthentication` has already validated the token by the time it reaches the pod for *external* traffic, but (per §2) that check doesn't apply to same-namespace callers, so `user-service` would happily "trust" a forged, unsigned JWT-shaped header sent by any compromised pod in `mbd`. It's a related gap worth flagging while addressing this issue, though it's outside `portfolio-service`'s own scope — the safe fix there is the same `spring-boot-starter-oauth2-resource-server` pattern, not a hand-rolled parser.

---

## 4. Recommended sequencing across the service mesh

Since the same gap (Spring Security absent) exists in every backend service except `admin-service` (per `CLAUDE.md`: "Only `admin-service` implements Spring Security... Other services rely on Istio mesh authentication"), the fix generalizes:

1. Add `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server` and the `SecurityConfig` shown above to `portfolio-service`, `account-service`, and `fund-service`.
2. Replace `user-service`'s manual, unverified JWT parsing with the same resource-server pattern (it needs the `sub` claim, which `Jwt.subject` provides once signature validation is in place).
3. Once every service independently validates JWTs, the `api-access-policy` AuthorizationPolicy's namespace-wide unauthenticated bypass (rule 1 in §2) stops being a single point of failure — it becomes a second layer instead of the only layer. Tightening or removing that bypass is a good follow-up but is not required to close *this* finding, since the application-level fix is sufficient on its own.

---

## 5. Verifying the fix doesn't break anything

- **Compile-time**: no controller signatures change; `Authentication`/`Jwt` injection is additive if/when Issue #3 is addressed later.
- **Runtime — external traffic**: already carries a valid JWT (frontend interceptor), so behavior is unchanged for legitimate users.
- **Runtime — probes**: explicitly `permitAll`'d for the exact paths the deployment's liveness/readiness/startup probes use.
- **Runtime — Swagger/OpenAPI**: explicitly `permitAll`'d, matching `admin-service`'s existing precedent, so `doc`'d local developer workflow (`kubectl port-forward` → Swagger UI) keeps working.
- **Tests**: `PortfolioIntegrationTest`, `PortfolioServiceTest`, `FundPriceUpdateConsumerTest` don't go through the HTTP layer, so none require changes.

---

## Summary

| Question | Answer |
|---|---|
| Fixable in current codebase? | **Yes** |
| Requires code changes? | Yes — 1 new `SecurityConfig` class (~15 lines), copied from `admin-service`'s existing pattern |
| Requires new infrastructure? | No — reuses the same Keycloak realm/JWKS endpoint already configured for `admin-service` and Istio |
| Requires frontend changes? | No — the Bearer token is already sent on every request |
| Breaks existing tests? | No — no existing test exercises the HTTP layer |
| Fully closes the OWASP finding? | Closes **authentication** (A07). Does not by itself close **authorization** (A01 / Issue #3) — that needs an additional ownership check layered on top of the now-validated identity |
| Related gap worth fixing alongside it | `AuthorizationPolicy`'s same-namespace JWT bypass (§2) and `user-service`'s unverified manual JWT parsing — both let the "no auth needed inside the mesh" assumption leak into places a compromised pod could exploit |
