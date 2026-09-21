# Fix Feasibility Analysis: Issue #1 — Unsafe Kafka Deserialization

**Date**: 2026-09-20
**Source finding**: `Three-Way-Security-Tool-Comparison.md`, "Issue #1: Unsafe Kafka Deserialization (CRITICAL)"
**Question answered**: Can this be fixed in the current codebase, and how?

---

## Verdict

**Yes — fully fixable, with a one-line configuration change per affected service. No code changes, no architectural changes, and no functional regression.**

The vulnerability is a Spring Kafka `JsonDeserializer` configured with a wildcard trust pattern instead of a scoped one. The codebase already only ever deserializes DTOs from a single package (`com.mbd.shared.dto`), so tightening the trust boundary requires no redesign — the fix removes an unnecessary over-permission, it doesn't work around a real dependency on the wildcard.

---

## 1. Confirming the current state

`portfolio-service/src/main/resources/application.yml:31`:

```yaml
spring:
  kafka:
    consumer:
      group-id: portfolio-service
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "*"
```

This is still present and unchanged in the current codebase — the issue is live, not previously remediated.

**The same pattern also exists in `fund-service`** (`fund-service/src/main/resources/application.yml:31`), consuming the `config-updates` topic. The comparison document scoped its review to portfolio-service only, but the identical misconfiguration exists in a second service and should be fixed at the same time.

| Service | Topic consumed | Consumer class | `trusted.packages` |
|---|---|---|---|
| `portfolio-service` | `fund-price-updates` | `FundPriceUpdateConsumer` | `"*"` |
| `fund-service` | `config-updates` | `ConfigUpdateConsumer` | `"*"` |

---

## 2. Why the wildcard is unnecessary here

Spring Kafka's `JsonDeserializer` reads the target Java/Kotlin type from the `__TypeId__` header that the producer's `JsonSerializer` attaches to every record (the fully-qualified class name of the object that was sent). `spring.json.trusted.packages` is an allow-list: if the package named in that header isn't on the list, deserialization is refused. A wildcard (`"*"`) disables this check entirely — Kafka will happily instantiate **any class on the classpath** named in an incoming (attacker-controlled, since mTLS/authn on the Kafka topic itself is not the point here — the point is defense-in-depth against a compromised or malicious producer) message header, which is a classic CWE-502 gadget-chain / RCE vector.

Tracing every producer and consumer in the codebase shows the trust boundary is much narrower than "anything":

```bash
$ grep -rn "kafkaTemplate.send\|KafkaTemplate<" backend --include=*.kt | grep -v build | grep -v /test/
admin-service/.../AdminConfigController.kt:  KafkaTemplate<String, FundConfigDto>  → topic "config-updates"
fund-service/.../FundPriceProducer.kt:       KafkaTemplate<String, FundPriceUpdate> → topic "fund-price-updates"
```

```bash
$ grep -rl "KafkaListener" backend --include=*.kt | grep -v build
portfolio-service/.../FundPriceUpdateConsumer.kt  →  fun handlePriceUpdate(update: FundPriceUpdate)
fund-service/.../ConfigUpdateConsumer.kt          →  fun handleConfigUpdate(config: FundConfigDto)
```

Both `FundPriceUpdate` and `FundConfigDto` live in the shared module at `backend/shared/src/main/kotlin/com/mbd/shared/dto/FundDto.kt`, i.e. package **`com.mbd.shared.dto`**. There is no producer or consumer anywhere in the backend that sends or expects a type from any other package over Kafka. The third defined topic, `portfolio-updates`, is unused (`PortfolioService.publishPortfolioUpdates` is a stub per the architecture doc), so it has no bearing on the fix.

---

## 3. The fix

Scope the trust list to the one package that is ever legitimately deserialized, in both services:

```yaml
# portfolio-service/src/main/resources/application.yml
spring:
  kafka:
    consumer:
      properties:
        spring.json.trusted.packages: "com.mbd.shared.dto"
```

```yaml
# fund-service/src/main/resources/application.yml
spring:
  kafka:
    consumer:
      properties:
        spring.json.trusted.packages: "com.mbd.shared.dto"
```

That's the entire code change. No Kotlin/Java changes are needed because:
- The `@KafkaListener` methods already declare concrete, correctly-typed parameters (`FundPriceUpdate`, `FundConfigDto`) — Spring uses the trust check only to decide whether it's *allowed* to instantiate the type named in the header; it doesn't change what type gets bound.
- No listener uses `Object`/wildcard/`@KafkaHandler` polymorphic dispatch that would require multiple packages to be trusted.

### Optional hardening (not required to close the finding, but cheap and worth doing while touching this config)

- Set an explicit `spring.json.value.default.type` per listener so the deserializer targets a fixed type even if the `__TypeId__` header is absent or spoofed, rather than trusting the header's class name at all:
  ```yaml
  spring.json.value.default.type: com.mbd.shared.dto.FundPriceUpdate
  spring.json.use.type.headers: false
  ```
  This is a stronger posture (pins the exact class rather than a whole package) but changes deserializer behavior more than the minimal fix above, so it's presented as optional rather than required.
- Same pattern for `fund-service` with `com.mbd.shared.dto.FundConfigDto`.

---

## 4. Verifying the fix doesn't break anything

Checked for any code path that would be broken by narrowing the trust list:

- **`PortfolioIntegrationTest`** (`portfolio-service/src/test/.../PortfolioIntegrationTest.kt`) is the only Kafka-related test. It runs against the real `application.yml` (no test-specific Kafka override exists) and publishes a `com.mbd.shared.dto.FundPriceUpdate` via `KafkaTemplate<String, Any>` — already inside the tightened trust boundary, so this test continues to pass unchanged.
- No other test resources or profiles override `spring.json.trusted.packages`.
- No consumer or producer in either service references a type outside `com.mbd.shared.dto`.

So the fix is a **drop-in, zero-risk change**: edit the two YAML lines, rebuild the two Docker images, `kind load docker-image`, `kubectl rollout restart deployment portfolio-service fund-service -n mbd`.

---

## 5. Why this belongs in the "manual review only" bucket, and what that implies

This confirms the comparison document's core point rather than complicating it: the vulnerability is real, trivially fixable, and was invisible to both CodeQL and Aikido's free tier purely because neither tool parses Spring Boot YAML for security-relevant properties — not because the fix required any deep architectural rework. A one-line grep for `trusted.packages:\s*"?\*"?` across `application.yml` files would have caught this without any AI or manual review at all; it simply isn't a rule either tool ships. That's a useful, narrower lesson than "automated tools can't find this class of bug" — this specific instance is closer to "nobody wrote a YAML-config linter rule for it yet."

---

## Summary

| Question | Answer |
|---|---|
| Fixable in current codebase? | **Yes** |
| Requires code changes? | No — config only |
| Requires architectural changes? | No |
| Scope of change | 1 line in `portfolio-service/application.yml`, 1 line in `fund-service/application.yml` (same bug, same fix) |
| Risk of regression | None identified — verified against all producers/consumers and the one Kafka integration test |
| Recommended value | `com.mbd.shared.dto` (matches every DTO ever sent on any topic in this codebase) |
