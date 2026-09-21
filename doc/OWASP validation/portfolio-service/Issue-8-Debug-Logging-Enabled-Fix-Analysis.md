# Fix Feasibility Analysis: Issue #8 — Debug Logging Enabled

**Date**: 2026-09-21
**Source finding**: `Three-Way-Security-Tool-Comparison.md`, "Issue #8: Debug logging enabled (LOW)"
**Question answered**: Can this be fixed in the current codebase, and how?
**Related to**: [`Issue-6-No-Audit-Logging-Fix-Analysis.md`](./Issue-6-No-Audit-Logging-Fix-Analysis.md) — that fix deliberately logs audit events at `INFO` specifically so they survive this issue being fixed; this document is what makes that precaution actually necessary.

---

## Verdict

**Yes — fixable, trivially, and the correct configuration already exists elsewhere in the same codebase.** `account-service`'s `application.yml` already sets exactly the values the other four services should use. This is a two-line config change per service, no code, no migration, no dependency.

Worth being upfront about scope: traced literally, this finding is currently **latent, not actively exploited** — there isn't a single `logger.debug(...)` call anywhere in the backend today, so the `DEBUG` threshold has nothing to act on yet. The value of fixing it is almost entirely preventive: it closes a footgun that the *other* issues in this series (#2, #3, #6, #7) are all about to make considerably more dangerous, since each of them proposes adding new logging to services that currently log almost nothing.

---

## 1. Confirming the current state

```bash
$ grep -rn "logging:" -A3 backend/*/src/main/resources/application.yml
portfolio-service: logging.level.com.mbd: DEBUG          # no `root:` override
admin-service:     logging.level.com.mbd: DEBUG          # no `root:` override
fund-service:      logging.level.com.mbd: DEBUG          # no `root:` override
user-service:      logging.level.com.mbd: DEBUG          # no `root:` override
account-service:   logging.level.com.mbd: INFO
                    logging.level.root: WARN
```

Four of the five backend services set `com.mbd: DEBUG`; `account-service` is the outlier — it's already configured the way the other four should be. This is the same "the fix pattern already exists in this codebase" situation as [Issue #2](./Issue-2-No-Authentication-Fix-Analysis.md) and [Issue #3](./Issue-3-No-Authorization-Fix-Analysis.md), where `account-service`/`admin-service` were also the services that happened to get something right that the others didn't.

There is also no Spring profile mechanism anywhere in the repo to separate "verbose for local development" from "what actually runs in the cluster":

```bash
$ find backend -iname "application-*.yml"
# (no matches — no application-local.yml, application-prod.yml, etc.)
$ grep -rn "spring.profiles.active\|SPRING_PROFILES_ACTIVE" infrastructure backend
# (no matches)
```

Whatever is in `application.yml` is exactly what runs when ArgoCD deploys the service to the Kind cluster — there's no separate, more conservative configuration for "production" versus "local." `DEBUG` here isn't a developer convenience that gets dialed back before deployment; it's the deployed setting.

### But: nothing currently logs at `DEBUG`

```bash
$ grep -rn "\.debug(" backend --include=*.kt
# (no matches anywhere in the entire backend)
```

The only logger calls anywhere in the codebase today are `GlobalExceptionHandler`'s `.warn()`/`.error()` calls in each service (and, if [Issue #6](./Issue-6-No-Audit-Logging-Fix-Analysis.md) and [Issue #7](./Issue-7-No-Kafka-Message-Validation-Fix-Analysis.md) are applied, their proposed `.info()`/`.warn()` audit/validation lines) — none of which are gated by the `DEBUG` threshold at all. Two other places `DEBUG` might have been expected to leak something were checked and ruled out:

- **Feign wire logging** (would log full request/response bodies, including `Authorization` headers, if enabled) requires an explicit `Logger.Level` bean or `feign.client.config.*.loggerLevel` property — neither exists anywhere in the codebase, so Feign's own internal level stays at its default `NONE` regardless of the SLF4J threshold. `com.mbd: DEBUG` alone does not turn this on.
- **Hibernate SQL logging** is explicitly disabled (`spring.jpa.show-sql: false`) and lives under a different logger namespace (`org.hibernate.SQL`) that `com.mbd: DEBUG` doesn't touch anyway.

So, as a pure snapshot of the code today, this setting has no observable effect. That's worth stating plainly rather than overclaiming — but it doesn't make the finding wrong, for the reasons in §2.

---

## 2. Why this is still worth fixing, precisely because nothing is being logged at DEBUG *yet*

### 2a. It inverts the safe default from opt-in to opt-out

With `com.mbd: DEBUG` set, **every future `logger.debug(...)` call any developer adds to any class under `com.mbd` becomes immediately visible in the deployed cluster's logs**, with no additional step, no environment gate, and no code review trigger specific to logging verbosity. The safe default is the other way around: verbose logging should require deliberately turning a knob up, not deliberately remembering to turn it back down before every deploy (which, given there's no profile separation at all — §1 — isn't even a real option today; there's only one config, and it's already maximally verbose).

### 2b. This is not a hypothetical future — four issues in this same document propose exactly the logging that would make it dangerous

- [Issue #6](./Issue-6-No-Audit-Logging-Fix-Analysis.md) explicitly calls out logging trade details at `INFO` specifically to avoid this trap, but that guidance only matters *because* the trap exists — if a future contributor adds a `logger.debug("trade attempt: {}", trade)` alongside it (an entirely natural thing to write while debugging a trade-related bug), it goes live immediately.
- [Issue #7](./Issue-7-No-Kafka-Message-Validation-Fix-Analysis.md) adds validation-rejection logging; the natural next debugging step when a rejection looks wrong is to add a `logger.debug("full message: {}", update)` — live immediately, no gate.
- [Issue #2](./Issue-2-No-Authentication-Fix-Analysis.md)/[Issue #3](./Issue-3-No-Authorization-Fix-Analysis.md) add Spring Security and JWT handling to these services for the first time. Debugging a new auth integration by logging the `Authentication`/`Jwt` object (`logger.debug("principal: {}", jwt)`) is one of the most common troubleshooting reflexes in Spring Security work — and depending on what's logged, that can mean bearer tokens or claim contents ending up in cluster logs.

None of this requires bad intent — it's exactly what normal, well-meaning debugging looks like. The problem isn't that anyone is currently doing it; it's that the codebase currently has zero friction stopping it from silently reaching production-equivalent logs the moment someone does.

### 2c. A second, smaller, already-live gap: no `root: WARN`

`account-service` sets `logging.level.root: WARN`; the other four don't set `root` at all, which leaves Spring Boot's default of `INFO` for every framework/library logger (Spring context startup, Hibernate dialect/DDL info, Kafka consumer group rebalancing, etc.). This one *is* live today — it's just noise rather than a data leak, but it's a genuine, present-tense side effect of the same misconfiguration, and it directly undercuts [Issue #6](./Issue-6-No-Audit-Logging-Fix-Analysis.md)'s fix: that document recommends logging audit events at `INFO` specifically so they're visible — but without `root: WARN`, `INFO` is also the level all the framework noise floods at, burying the audit signal in it. Fixing both settings together is what actually makes "log audit events at INFO" a usable signal rather than one line in a flood.

### CWE note

The source comparison table leaves this finding's CWE blank. The precise match is **CWE-215: Insertion of Sensitive Information Into Debug Log File** — worth recording for consistency with how the other seven issues are documented.

---

## 3. The fix

Change all four affected services to match `account-service` exactly:

```yaml
logging:
  level:
    com.mbd: INFO
    root: WARN
```

Applies to `portfolio-service`, `admin-service`, `fund-service`, and `user-service`'s `application.yml`. No code changes, no new dependencies — this is the entire fix.

### Optional, future hardening (not required to close the finding)

If local-development convenience for verbose logging is wanted later, the idiomatic Spring Boot way is a dedicated profile (`application-local.yml` with `com.mbd: DEBUG`, activated only via `SPRING_PROFILES_ACTIVE=local` on a developer's machine) rather than making the deployed default verbose. This is a genuine improvement worth suggesting, but it's additive — the two-line change above fully closes the finding as reported without it.

---

## 4. Verifying the fix doesn't break anything

- **No log statements are silently lost**: since nothing in the codebase currently calls `.debug(...)` (checked, §1), raising the threshold from `DEBUG` to `INFO` doesn't hide anything that exists today.
- **Issue #6's audit lines stay visible**: proposed at `.info(...)`, which is exactly the new threshold — unaffected.
- **Issue #7's validation-rejection lines stay visible**: proposed at `.warn(...)`, above the new threshold — unaffected.
- **`GlobalExceptionHandler`'s existing `.warn()`/`.error()` calls stay visible**: both above `INFO` — unaffected.
- **No test asserts on logging output or level** — checked, no test in any service's `src/test` references `Logger`, log level, or log content assertions.
- **Operational impact**: strictly a reduction in framework-level noise (`root: WARN`) plus removal of a currently-inert `DEBUG` threshold — nothing that any existing dashboard, alert, or documented workflow (`doc/operation-notes.md`) depends on.

---

## 5. What this fix does not cover

This closes the specific finding ("debug logging enabled") by removing the blanket permissive threshold. It does not, by itself, prevent a future `logger.info(...)` or `logger.warn(...)` call from logging something sensitive (a JWT, an account balance, a full request body) — raising the threshold reduces the *volume* of what's eligible to be logged, it doesn't inspect *content*. A more complete answer to "sensitive data might end up in logs" would involve things like structured logging with field-level redaction, or a log-scanning/DLP step in CI — genuinely useful, but a larger, separate initiative, not part of what "debug logging enabled" as reported is asking to fix.

---

## Summary

| Question | Answer |
|---|---|
| Fixable in current codebase? | **Yes** |
| Requires code changes? | No — config only, 2 lines per service, 4 services |
| Requires new infrastructure? | No |
| Pattern already proven in this codebase? | Yes — `account-service`'s `application.yml` already uses the exact target configuration |
| Current observable impact | None today — zero `.debug()` calls exist anywhere in the backend; the risk is entirely forward-looking |
| Why fix it anyway | It's the safety net for four other issues in this series (#2, #3, #6, #7) that are all about to add new logging to these exact services; fixing it now is cheap, fixing it after someone's debug statement has already shipped sensitive data to logs is not |
| CWE | CWE-215 – Insertion of Sensitive Information Into Debug Log File (blank in the source table) |
| OWASP Category | A02:2025 – Security Misconfiguration (also A09 – Security Logging and Alerting Failures, for the `root: WARN` / signal-to-noise angle) |
| Breaks existing tests? | No — no test asserts on logging output or level |
| Depends on | None — fully independent, though its value compounds with [Issue #6](./Issue-6-No-Audit-Logging-Fix-Analysis.md)/[Issue #7](./Issue-7-No-Kafka-Message-Validation-Fix-Analysis.md) landing |
