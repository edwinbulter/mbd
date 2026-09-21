# Fix Feasibility Analysis: Issue #7 — No Kafka Message Validation

**Date**: 2026-09-21
**Source finding**: `Three-Way-Security-Tool-Comparison.md`, "Issue #7: No Kafka message validation (MEDIUM)"
**Question answered**: Can this be fixed in the current codebase, and how?
**Related to**: [`Issue-1-Kafka-Deserialization-Fix-Analysis.md`](./Issue-1-Kafka-Deserialization-Fix-Analysis.md) — Issue #1 is about whether the deserializer is allowed to construct a given class at all; this issue is about whether the *content* of a message it's already allowed to construct makes sense before anything acts on it. Fixing one doesn't fix the other.

---

## Verdict

**Yes — fixable, and the dependency for the standard fix (Jakarta Bean Validation) is already on the classpath of every affected service, completely unused.** Both Kafka consumers in scope (`portfolio-service`'s `FundPriceUpdateConsumer` and `fund-service`'s `ConfigUpdateConsumer`) act on deserialized message content with zero bounds checking. Tracing the surrounding infrastructure turned up that this isn't a hypothetical "what if someone sends a bad message" concern — **Kafka in this cluster has no authentication or ACLs at all**, so the consumers are the only thing standing between a stray or malicious publisher and corrupted portfolio data.

---

## 1. Confirming the current state

### 1a. `portfolio-service`: price updates applied with no sanity check

`FundPriceUpdateConsumer.handlePriceUpdate`:

```kotlin
@KafkaListener(topics = ["fund-price-updates"], groupId = "portfolio-service")
@Transactional
fun handlePriceUpdate(update: FundPriceUpdate) {
    val holdings = holdingRepository.findByFundId(update.fundId)
    holdings.forEach { holding ->
        holding.currentValue = holding.quantity.multiply(update.newPrice)   // no check that newPrice > 0
        ...
    }
    // ...totalValue computed from the above and persisted into portfolio_value_history
}
```

`update.newPrice` — a `BigDecimal` with no constraint — is multiplied directly into `holding.currentValue` and, via the snapshot logic further down, into a row written to `portfolio_value_history`. A zero, negative, or absurdly large price is accepted exactly the same as a legitimate one: it silently corrupts every affected customer's displayed portfolio value and the persisted history chart data, with no error, no rejection, no log line.

### 1b. `fund-service`: config applied to every fund with no sanity check

`ConfigUpdateConsumer.handleConfigUpdate`:

```kotlin
@KafkaListener(topics = ["config-updates"], groupId = "fund-service")
fun handleConfigUpdate(config: FundConfigDto) {
    val funds = fundRepository.findAll()
    funds.forEach { fund ->
        fund.volatility = config.volatility                              // no bounds check
        fund.updateFrequencyMinutes = config.updateFrequencyMinutes       // no bounds check
        fund.updatedAt = java.time.LocalDateTime.now()
        fundRepository.save(fund)
    }
}
```

This is applied unconditionally to **every fund in the system**. `PriceUpdateScheduler` later uses these values as:

```kotlin
val nextUpdate = fund.updatedAt.plusMinutes(fund.updateFrequencyMinutes.toLong())
val randomFactor = Random.nextDouble(-volatility, volatility)
```

A `updateFrequencyMinutes` of `0` (or negative) makes `nextUpdate` never after `now`, so every scheduler tick (once a minute) immediately republishes a new price for every fund — a self-inflicted tight loop of Kafka messages. `kotlin.random.Random.nextDouble(-volatility, volatility)` requires `volatility` to describe a valid, non-empty range; a negative `volatility` throws `IllegalArgumentException` **inside the scheduled task**, which (depending on how the exception propagates through `@Scheduled`) can silently stop that scheduler from ever running again for every fund, not just one.

### 1c. The DTOs carry no constraints, and the tooling to add them is already present but unused

```kotlin
data class FundConfigDto(val volatility: Double, val updateFrequencyMinutes: Int)
data class FundPriceUpdate(val fundId: Long, val newPrice: BigDecimal, val timestamp: LocalDateTime = LocalDateTime.now())
```

No `@field:Positive`, `@field:DecimalMin`, or any other Jakarta Bean Validation annotation on either DTO. That's despite `spring-boot-starter-validation` already being a declared dependency of both `portfolio-service` and `fund-service`:

```bash
$ grep -rn "@Valid\b" backend --include=*.kt
# (no matches anywhere in the entire backend)
```

**`@Valid`/`@Validated` is used nowhere in this codebase at all** — not on Kafka listeners, not on REST controllers either. `GlobalExceptionHandler` in every service has a dedicated `handleValidationExceptions(ex: MethodArgumentNotValidException)` handler, which can only ever fire if something upstream uses `@Valid` — meaning that handler is dead code today, written in anticipation of validation that was never wired up. This is useful context: the fix for this issue isn't introducing a new pattern to the codebase, it's finishing one that's half-built.

---

## 2. Why this is a real gap, not a theoretical one

Two things found while tracing the surrounding infrastructure make this more concrete than "messages should generally be validated":

### 2a. Kafka has no authentication or ACLs — the consumer is the only gate

`infrastructure/k8s/kafka/configmap.yaml`:

```yaml
KAFKA_LISTENERS: "PLAINTEXT://:9092,BROKER://:9093"
KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: "BROKER:PLAINTEXT,PLAINTEXT:PLAINTEXT"
```

Kafka runs fully `PLAINTEXT`, with no SASL, no TLS, and no ACLs configured anywhere in the repo. Anything that can reach `kafka.mbd-infra.svc.cluster.local:9092` — any pod in the mesh, not just `admin-service` or `fund-service` — can publish directly to `fund-price-updates` or `config-updates` using a plain Kafka client, completely bypassing `admin-service`'s `@PreAuthorize("hasRole('admin')")` REST endpoint and `fund-service`'s scheduler. The two `@KafkaListener` methods are not a second line of defense behind an authenticated API — for anyone who can reach the broker, **they're the only check that exists at all.**

### 2b. Even the legitimate producer path has no validation either

`AdminConfigController.updatePriceUpdateConfig` (the one authenticated path that's supposed to produce these messages) takes `@RequestBody config: FundConfigDto` and persists + publishes it with zero validation:

```kotlin
fun updatePriceUpdateConfig(@RequestBody config: FundConfigDto): ResponseEntity<FundConfigDto> {
    // ...saves config.updateFrequencyMinutes and config.volatility to SystemConfig, unchecked
    kafkaTemplate.send("config-updates", "config", config)
    ...
}
```

So a typo by an authorized admin (e.g. `updateFrequencyMinutes: 0`, or a `volatility` of `5.0` meaning "500%") reaches every fund in the system exactly the same way a malicious message would — there's no gate anywhere in the pipeline, not at the producer and not at the consumer.

### 2c. No dead-letter handling means a malformed message can be worse than a bad one

No `ErrorHandlingDeserializer`, no custom `CommonErrorHandler`, and no dead-letter topic are configured anywhere in the codebase (checked both services' `application.yml` and all Kafka-related Kotlin files). This matters for a different failure mode than §1–2b: a **structurally malformed** message (invalid JSON, or JSON that doesn't match the target type at all — as opposed to a well-formed message with an out-of-range value) fails inside the `JsonDeserializer` itself, during `poll()`, before the record ever reaches the `@KafkaListener` method body. Without `ErrorHandlingDeserializer` wrapping the deserializer, that failure isn't caught by the listener container's error handling the way an exception thrown *inside* the listener method would be — it can stop the consumer thread outright, halting all further processing on that topic until the pod is restarted. A single bad message is a worse outcome than a stream of bad-but-well-formed ones, and nothing here currently guards against it.

---

## 3. The fix

### 3a. Content validation — activate the Bean Validation setup that's already half-built

Add constraints to the DTOs:

```kotlin
data class FundConfigDto(
    @field:DecimalMin(value = "0.0001", message = "volatility must be positive")
    @field:DecimalMax(value = "1.0", message = "volatility must not exceed 1.0 (100%)")
    val volatility: Double,

    @field:Positive(message = "updateFrequencyMinutes must be positive")
    val updateFrequencyMinutes: Int
)

data class FundPriceUpdate(
    val fundId: Long,
    @field:DecimalMin(value = "0.01", message = "newPrice must be positive")
    val newPrice: BigDecimal,
    val timestamp: LocalDateTime = LocalDateTime.now()
)
```

(The exact bounds on `volatility` are a business decision, not a technical one — `0.0001`–`1.0` is shown as a reasonable illustrative range given how `PriceUpdateScheduler` uses it; the team should confirm the real ceiling.)

Then validate explicitly at the top of each listener, rather than relying on `@Validated`/`@Valid` AOP interception — explicit validation gives full control over the log message and lets the record be skipped (offset still commits, consumer keeps running) instead of thrown as an exception that depends on Spring Kafka's default retry/skip behavior to behave safely:

```kotlin
@Service
class FundPriceUpdateConsumer(
    private val holdingRepository: HoldingRepository,
    private val snapshotRepository: PortfolioValueSnapshotRepository,
    private val validator: jakarta.validation.Validator   // auto-configured LocalValidatorFactoryBean, already on the classpath
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(topics = ["fund-price-updates"], groupId = "portfolio-service")
    @Transactional
    fun handlePriceUpdate(update: FundPriceUpdate) {
        val violations = validator.validate(update)
        if (violations.isNotEmpty()) {
            log.warn("Rejected invalid fund-price-updates message: fundId={} newPrice={} violations={}",
                update.fundId, update.newPrice, violations.joinToString { it.message })
            return   // skip this record; offset still commits, consumer keeps running
        }
        // ...existing logic unchanged
    }
}
```

The same shape applies to `ConfigUpdateConsumer.handleConfigUpdate` in `fund-service`.

This is the same explicit "log and skip" style already used for audit outcomes in [Issue #6](./Issue-6-No-Audit-Logging-Fix-Analysis.md) — worth keeping consistent, since both are examples of "an event arrived, we made a decision about it, and that decision should be visible in the logs" rather than a silently swallowed no-op.

### 3b. Structural resilience — stop a malformed message from being able to kill the consumer

Wrap the deserializer so a record that can't even be parsed becomes a normal, catchable listener-level failure instead of a container-killing one:

```yaml
spring:
  kafka:
    consumer:
      value-deserializer: org.springframework.kafka.support.serializer.ErrorHandlingDeserializer
      properties:
        spring.deserializer.value.delegate.class: org.springframework.kafka.support.serializer.JsonDeserializer
        spring.json.trusted.packages: "com.mbd.shared.dto"   # from the Issue #1 fix
```

With `ErrorHandlingDeserializer` in place, Spring Kafka's default `DefaultErrorHandler` (already active with no configuration needed) logs the failure and advances past the bad record instead of the container dying. For visibility into how often this actually happens, a `DeadLetterPublishingRecoverer` can be added as a `CommonErrorHandler` bean to route failed records to a `<topic>.DLT` topic for later inspection — a genuinely useful addition, but the `ErrorHandlingDeserializer` wrap alone is what prevents the availability failure; the DLQ is an observability nicety on top.

### 3c. Optional, complementary: validate at the producer too

Adding the same `@field:` constraints plus `@Valid` on `AdminConfigController.updatePriceUpdateConfig`'s `@RequestBody config: FundConfigDto` closes §2b — it's a normal Spring MVC controller, so `@Valid` there *does* work out of the box and would finally give `GlobalExceptionHandler`'s existing `MethodArgumentNotValidException` handler something to catch. Not required to close this specific finding (which is about Kafka message validation), but it's the same one-line annotation addition and removes the "an admin's typo has no safety net" gap noted in §2b.

---

## 4. Verifying the fix doesn't break anything

- **`FundPriceUpdateConsumerTest`**: all three existing test cases use `newPrice = BigDecimal("110.00")` — comfortably within the new constraint. Unaffected; a new test asserting the rejection path (`newPrice = BigDecimal("-5.00")` → `holdingRepository.save` never called) should be added alongside the fix.
- **`ConfigUpdateConsumerTest`**: uses `volatility = 0.03`, `updateFrequencyMinutes = 2` — within the proposed bounds. Unaffected; same note about adding a rejection-path test.
- **Constructor change**: injecting `jakarta.validation.Validator` into `FundPriceUpdateConsumer`/`ConfigUpdateConsumer` means their existing unit tests' `@InjectMocks` setup needs one more `@Mock`, or (simpler, since `Validator` is a real, side-effect-free object) the tests can just construct a real `LocalValidatorFactoryBean` instead of mocking it — mechanical either way, no behavioral assertions change.
- **`PortfolioIntegrationTest`**: publishes a `FundPriceUpdate` with a positive price via Testcontainers Kafka — unaffected by the new validation.
- **Legitimate traffic**: `PriceUpdateScheduler.calculateRandomPrice` always produces a positive price from a positive starting price (bounded random walk), and `AdminConfigController`'s current defaults (`0.02`, `5`) are within the proposed bounds — no legitimate message is rejected by this fix.

---

## 5. What this fix does not cover

Closing "can a bad message get *acted on*" (this issue) is different from closing "can anyone publish to this topic at all" (§2a). The latter needs Kafka-level SASL/ACL configuration — a real infrastructure change (broker config, client credentials, credential distribution to five services) rather than an application code fix, and is out of scope for what "fixable in the current codebase" reasonably covers here. It's flagged as a legitimate follow-up, not silently ignored: even after this fix, an attacker who can reach the Kafka broker can still spam validly-bounded-but-wrong price updates (e.g. a price that's technically positive but wrong) — validation catches nonsensical values, not all falsified ones. Only broker-level authentication closes that fully.

---

## Summary

| Question | Answer |
|---|---|
| Fixable in current codebase? | **Yes** |
| Requires code changes? | Yes — Bean Validation constraints on 2 DTOs, an explicit validation check in each of the 2 consumers, plus a 2-line `application.yml` change per service for `ErrorHandlingDeserializer` |
| Requires new infrastructure? | No — `spring-boot-starter-validation` is already a dependency of both services, just unused until now |
| Key discovery | Kafka has no authentication/ACLs (`PLAINTEXT` only) — the two `@KafkaListener` methods are the *only* check standing between any pod in the mesh and corrupted fund prices/config, not a defense-in-depth layer behind an authenticated API |
| OWASP Category | A08:2025 – Software or Data Integrity Failures (also A06 – Insecure Design, for the missing structural resilience around deserialization failures) |
| Breaks existing tests? | No — existing test fixtures all use in-bounds values; new tests should be added for the rejection paths |
| Fully closes the finding? | Closes content validation and consumer-availability resilience. Does not close "who can publish to this topic at all" — that needs Kafka-level SASL/ACLs, a genuine infrastructure change, flagged as separate follow-up work |
| Depends on | None — independent of every other issue in this series, though it shares the same DTOs/trust-boundary discussion as [Issue #1](./Issue-1-Kafka-Deserialization-Fix-Analysis.md) |
