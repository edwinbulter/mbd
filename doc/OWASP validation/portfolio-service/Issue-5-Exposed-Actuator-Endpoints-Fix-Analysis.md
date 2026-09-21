# Fix Feasibility Analysis: Issue #5 — Exposed Actuator Endpoints

**Date**: 2026-09-21
**Source finding**: `Three-Way-Security-Tool-Comparison.md`, "Issue #5: Exposed Actuator Endpoints (MEDIUM)"
**Question answered**: Can this be fixed in the current codebase, and how?
**Related to**: [`Issue-2-No-Authentication-Fix-Analysis.md`](./Issue-2-No-Authentication-Fix-Analysis.md) — the strongest version of this fix depends on the Spring Security setup proposed there, but part of the fix is independent and should happen regardless.

---

## Verdict

**Yes — fixable, and most of it is a config trim that can happen immediately, independent of any other issue.** Two changes to `application.yml` (shrink the exposed endpoint list, stop dumping full health detail to unauthenticated callers) close the bulk of the finding today, with zero dependency on anything else in this series. A third, stronger layer — actually requiring authentication on the non-health actuator paths — depends on [Issue #2](./Issue-2-No-Authentication-Fix-Analysis.md)'s Spring Security work, and turns out to be the *only* layer that can protect against one real, already-documented access path this investigation turned up: `kubectl port-forward`.

---

## 1. Confirming the current state

`portfolio-service/src/main/resources/application.yml:33-42`:

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
        enabled: true
```

Two separate problems here:
- `exposure.include` publishes `/actuator/health`, `/actuator/info`, `/actuator/metrics`, and `/actuator/prometheus` over HTTP, with no `spring-boot-starter-security` on the classpath (confirmed in the [Issue #2](./Issue-2-No-Authentication-Fix-Analysis.md) analysis) to restrict any of them.
- `show-details: always` means `/actuator/health` returns full component-level detail (database connectivity status, disk-space totals/thresholds, and — since `spring-kafka` is on the classpath — Kafka broker/cluster reachability) to **anyone**, not just an authenticated operator. Spring Boot's own default is `never` precisely because `always` is considered too permissive for a production profile.

The same `include: health,info,metrics,prometheus` block is copy-pasted into every other backend service's `application.yml` (`account-service` has a slightly narrower `health,prometheus`; `admin-service`, `fund-service`, and `user-service` all match `portfolio-service` exactly), so this is boilerplate repeated five times over, not a one-off.

---

## 2. How reachable is this, actually? (Three separate paths, checked individually)

The finding calls these endpoints "exposed" without qualifying to whom. That distinction matters for prioritizing the fix, so each path was traced:

### 2a. Through the public Gateway (`customer.mbd.local` / `admin.mbd.local`) — **not reachable**

Every `VirtualService` in `infrastructure/k8s/istio/*-vs.yaml` was checked for an `/actuator` route:

```bash
$ grep -rn "actuator" infrastructure/k8s/istio/*-vs.yaml
# (no matches)
```

`portfolio-service-vs.yaml` only routes `/api/portfolio`, `/swagger-ui`, and `/v3/api-docs` prefixes to the service; the same is true for every other service's `VirtualService`. There is no route anywhere that forwards `/actuator/*` through the Gateway, so a plain internet request to `https://portfolio.customer.mbd.local/actuator/health` (or any equivalent) never reaches `portfolio-service` — Envoy at the Gateway returns 404 before the request goes anywhere. **The most obvious version of "exposed to the internet" doesn't apply here.**

### 2b. Pod-to-pod inside the `mbd` namespace — likely already denied, but only as an Istio-policy side effect

`api-access-policy` (`infrastructure/k8s/istio/authorization-policy.yaml`, no `selector` → applies to every workload in `mbd`, per the mesh's own `README.md` §3.4) only lists `/api/*` and `/swagger-ui`/`/v3/api-docs` paths in its `ALLOW` rules. Istio's documented `AuthorizationPolicy` semantics are: once *any* `ALLOW` policy selects a workload, that workload denies everything that doesn't match one of its rules — there's no separate default-allow fallback once an `ALLOW` policy is in play. `/actuator/*` matches none of `api-access-policy`'s rules, so on paper, even a same-namespace pod calling `portfolio-service.mbd.svc.cluster.local:8080/actuator/health` directly should be rejected by the sidecar before it reaches the app.

This is stated with appropriate hedging: it follows from Istio's documented policy semantics and the policy file as written, but it was **not verified against a running cluster** as part of this analysis (no cluster was available), so treat it as "should already be blocked by design," not "confirmed blocked in practice." It's also incidental protection — nobody wrote this `AuthorizationPolicy` with `/actuator` in mind, it's just a side effect of `/api/*` being the only thing enumerated.

### 2c. `kubectl port-forward` — **confirmed reachable, and it's a documented, actively-used developer workflow**

This is the one path that's concretely exploitable, and it's not hypothetical — it's the project's own documented way of reaching these services. `CLAUDE.md` itself instructs:

```bash
kubectl port-forward svc/fund-service -n mbd 9080:8080
# Open http://localhost:9080/swagger-ui.html
# Also works for: portfolio-service, ...
```

`kubectl port-forward` works by having the kubelet attach directly inside the target pod's network namespace and stream bytes straight to `localhost:<containerPort>` — it does **not** traverse the pod's normal network interface, so it never passes through the iptables rules Istio's init container installs to redirect inbound traffic to the Envoy sidecar. In other words: **port-forwarded traffic bypasses mTLS, `RequestAuthentication`, and `AuthorizationPolicy` entirely**, landing directly on the raw Spring Boot listener. This is exactly why the documented workflow above works at all despite `PeerAuthentication` being `STRICT` mesh-wide — and it means `/actuator/health` (with full `show-details: always` output), `/actuator/metrics`, and `/actuator/prometheus` are reachable, today, by **anyone who can run `kubectl port-forward` against the `mbd` namespace** — i.e. anyone with a working kubeconfig and namespace access, independent of anything Istio is configured to do.

**Conclusion**: the finding is real, but its actual blast radius is narrower than "public internet" — it's "anyone with cluster/kubectl access," which for this demo project is every developer and every CI credential with a kubeconfig, not an anonymous internet attacker. That's still worth fixing (least privilege, defense-in-depth, and this narrower audience is exactly the one a compromised laptop or leaked kubeconfig would hand access to) — and critically, §2c is a vector that **no Istio configuration can close**, since Istio never sees that traffic. Only an application-level fix reaches it.

---

## 3. The fix

### 3a. Shrink the exposed endpoint list (do this regardless of anything else)

Nothing in this stack scrapes `/actuator/metrics` or `/actuator/prometheus` today — a repo-wide search turns up no Prometheus, Grafana, or `ServiceMonitor` deployment anywhere under `infrastructure/`:

```bash
$ find infrastructure -iname "*prometheus*" -o -iname "*servicemonitor*" -o -iname "*grafana*"
# (no matches)
```

So those two endpoints are pure unused attack surface — they cost nothing to remove and buy nothing by staying. `/actuator/info` currently returns an empty `{}` (no `git-commit-id` or build-info plugin populates it — checked `build.gradle.kts`), so it's harmless today, but it's also unused, and leaving it "on by default" is exactly how a future build-info plugin addition would silently start leaking commit hashes/build timestamps without anyone revisiting this list. Kubernetes' liveness/readiness/startup probes (`infrastructure/k8s/portfolio-service/deployment.yaml:28-45`) only ever hit `/actuator/health/liveness` and `/actuator/health/readiness` — `health` is the only endpoint actually required.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
      probes:
        enabled: true
```

`show-details: never` means `/actuator/health` returns just `{"status":"UP"}` or `{"status":"DOWN"}` — Kubernetes' probes only ever check the HTTP status code (200 vs. 503), never the JSON body, so this doesn't affect probe behavior at all; it only stops leaking DB/disk/Kafka internals to whoever can reach the endpoint.

### 3b. Once Issue #2 lands, require authentication on everything beyond health

This is the layer that actually closes §2c (`kubectl port-forward`), because it's enforced inside the Spring Boot process itself — it doesn't matter how the request physically arrived (through Envoy or straight through a port-forward), Spring Security's filter chain sees it either way. The [Issue #2 analysis](./Issue-2-No-Authentication-Fix-Analysis.md) already proposed this exact scoping for `portfolio-service`'s `SecurityConfig`, deliberately narrower than `admin-service`'s existing `permitAll("/actuator/**")`:

```kotlin
.requestMatchers("/actuator/health/**").permitAll()   // kubelet probes carry no JWT
.anyRequest().authenticated()                          // everything else, including any other /actuator/** path
```

With 3a already trimming the exposure list to just `health`, this second layer is defense-in-depth rather than load-bearing on its own — but it's what makes the fix robust against §2c specifically, and against `admin-service`'s own looser `permitAll("/actuator/**")` pattern being copied forward carelessly if someone re-adds `metrics`/`prometheus` to the include list later.

### 3c. Apply the same exposure trim to the other four services

Since the same `include: health,info,metrics,prometheus` block (or `account-service`'s `health,prometheus`) is repeated in every service's `application.yml`, and none of them are scraped by anything in this stack either, the same one-line change (`include: health`) applies uniformly to `account-service`, `admin-service`, `fund-service`, and `user-service`. `admin-service` should additionally tighten its `SecurityConfig` from `permitAll("/actuator/**")` to `permitAll("/actuator/health/**")`, matching the narrower pattern proposed for `portfolio-service`.

---

## 4. Verifying the fix doesn't break anything

- **Kubernetes probes**: `livenessProbe`/`readinessProbe`/`startupProbe` all target `/actuator/health/liveness` and `/actuator/health/readiness`, which stay exposed and unauthenticated under 3a+3b — unaffected by either change.
- **Swagger/developer workflow**: unaffected — Swagger UI is served from `/swagger-ui.html` and `/v3/api-docs`, not from `/actuator/*`, and both were already explicitly `permitAll`'d in the Issue #2 `SecurityConfig` proposal.
- **Tests**: no test in `portfolio-service/src/test` references `/actuator` in any form (checked) — nothing to update.
- **Local/dev tooling**: nothing in this repo's documented workflows (`doc/operation-notes.md`, `CLAUDE.md`) curls `/actuator/metrics` or `/actuator/prometheus` — those two are simply dead surface today.

---

## 5. Sequencing

1. **3a (exposure list + `show-details: never`) has no dependency on anything else** — it's a pure YAML trim, safe to do immediately across all five services, and it's the change that removes the two endpoints (`metrics`, `prometheus`) that currently have zero legitimate consumer.
2. **3b (auth-gate the remainder) depends on Issue #2's `SecurityConfig`** landing first, since there's currently no Spring Security filter chain in `portfolio-service` to attach the `/actuator/health/**` vs. everything-else distinction to.
3. Given 3a alone already removes every endpoint except `health`, and `show-details: never` neuters what `health` itself can leak, 3b's marginal value narrows to "in case an endpoint gets added back to the include list later without re-reviewing this decision" — worth doing when Issue #2 lands, but 3a is what actually resolves the finding as reported.

---

## Summary

| Question | Answer |
|---|---|
| Fixable in current codebase? | **Yes** |
| Requires code changes? | No — config only (`application.yml`, 5 services); the auth-gating layer (3b) reuses the `SecurityConfig` already proposed for Issue #2 |
| Requires new infrastructure? | No |
| Actual exposure today | Not reachable through the public Gateway (no route exists in any `VirtualService`); likely denied mesh-internally by `api-access-policy`'s default-deny semantics (not verified live); **confirmed reachable via `kubectl port-forward`**, which bypasses Istio entirely and is the project's own documented access method |
| Key discovery | `kubectl port-forward` sidesteps mTLS/`RequestAuthentication`/`AuthorizationPolicy` completely, since it never traverses the sidecar — meaning this is the one finding in the series where **only an application-level fix (not any Istio config) can fully close the gap** |
| Breaks existing tests or probes? | No — probes only check `health`'s HTTP status code, never checked elsewhere in tests |
| Depends on | [Issue #2](./Issue-2-No-Authentication-Fix-Analysis.md) for the auth-gating layer (3b) only; the exposure-list trim (3a) is independent and should be done immediately |
