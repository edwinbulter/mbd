# Fix Feasibility Analysis: Issue #6 — No Audit Logging for Trades

**Date**: 2026-09-21
**Source finding**: `Three-Way-Security-Tool-Comparison.md`, "Issue #6: No audit logging for trades (MEDIUM)"
**Question answered**: Can this be fixed in the current codebase, and how?
**Related to**: [`Issue-4-Race-Condition-Fix-Analysis.md`](./Issue-4-Race-Condition-Fix-Analysis.md) (the same cross-service atomicity limit applies to what an audit trail can guarantee) and [`Issue-2`](./Issue-2-No-Authentication-Fix-Analysis.md)/[`Issue-3`](./Issue-3-No-Authorization-Fix-Analysis.md) (a caller identity to attribute trades to depends on those landing).

---

## Verdict

**Yes — fixable, and worth doing in two layers.** A structured application-level audit log for every trade attempt (success and failure) is a same-day change with no schema migration. A persisted, append-only `trade_audit_log` table — needed for anything resembling real compliance record-keeping, not just operational visibility — is a small Flyway migration plus one repository and a few lines in `PortfolioService`. Neither requires new infrastructure.

Tracing the current code turned up that the gap is more complete than "logging isn't audit-grade" — **there is currently zero record, anywhere, of an individual trade ever having happened.** That's worth establishing precisely before proposing the fix.

---

## 1. Confirming the current state

### 1a. `portfolio-service` never logs a trade

```bash
$ grep -rn "Logger\|LoggerFactory\|logger\." backend/portfolio-service/src/main --include=*.kt
GlobalExceptionHandler.kt:4:import org.slf4j.LoggerFactory
GlobalExceptionHandler.kt:15:    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
GlobalExceptionHandler.kt:23:        logger.warn("Validation failed: {}", errors)
GlobalExceptionHandler.kt:34:        logger.warn("ResponseStatusException: {}", ex.reason, ex)
GlobalExceptionHandler.kt:44:        logger.warn("Bad request exception: {}", ex.message, ex)
GlobalExceptionHandler.kt:54:        logger.error("Unexpected exception occurred", ex)
```

The only logging anywhere in the service is `GlobalExceptionHandler`'s generic exception handling. That means:
- A **failed** trade (e.g. `IllegalStateException("Insufficient balance")`) is logged today — but only as a generic "Bad request exception" line with the exception message, not tagged as a trade event, and with no account/fund/quantity/caller context beyond whatever happens to be in the message string.
- A **successful** `BUY` or `SELL` — the actual financially-significant event — produces **no log line at all**. `PortfolioController.executeTrade` and `PortfolioService.executeBuy`/`executeSell` (`backend/portfolio-service/src/main/kotlin/com/mbd/portfolio/...`) return a `200 OK` with no logging anywhere on the success path.

### 1b. Nothing in the database records individual trades either

The full migration history for `portfolio-service` is two files:

```
V1__Create_Holdings_Table.sql        → holdings (mutated in place per trade — no history kept)
V2__Create_Portfolio_Value_History_Table.sql → portfolio_value_history (aggregate value snapshots, written by the Kafka price consumer, not per-trade)
```

`holdings` stores the **current** position (`quantity`, `averagePrice`, `currentValue`) and is overwritten on every `BUY`/`SELL` — the row for a given `(accountId, fundId)` after ten trades looks identical whether it's had one trade or ten; the individual trade events that produced the current state aren't retained anywhere. `portfolio_value_history` records portfolio-wide value snapshots on a price-update cadence, not discrete trade events.

The closest thing that exists is `account-service`'s `transactions` table (`accountId`, `amount`, `type`, `description`, `createdAt`), written as a side effect of the balance debit/credit Feign call. It records that *money moved*, but:
- **No actor**: no `userId`, no Keycloak subject — it can't answer "who executed this."
- **No trade detail**: no `fundId`, `quantity`, or `price` — it can't answer "what was bought/sold," only "the balance changed by €X."
- **No linkage**: nothing ties a `transactions` row back to the specific trade in `portfolio-service` that caused it, or to the resulting `holdings` change.

**Net finding**: there is currently no way to answer "which user bought/sold what, how much, at what price, and when" for any trade ever executed in this system, from any table or any log — the finding's severity (MEDIUM) undersells how complete the gap actually is for a service handling financial transactions.

---

## 2. What an audit trail needs to contain here

For a trading endpoint, "audit logging" conventionally means recording, for every attempt (not just successes):
- **Who**: the authenticated caller's identity (depends on [Issue #2](./Issue-2-No-Authentication-Fix-Analysis.md) resolving a verified JWT subject, and ideally [Issue #3](./Issue-3-No-Authorization-Fix-Analysis.md)'s resolved internal user id)
- **What**: account, fund, trade type (`BUY`/`SELL`), quantity, price, resulting total
- **When**: a timestamp
- **Outcome**: succeeded, or rejected and why (insufficient balance, insufficient quantity, invalid fund, etc.)

This directly maps to **OWASP A09:2025 – Security Logging and Alerting Failures** (OWASP Top 10:2025, released January 2026 — this was A09:2021 "Security Logging and Monitoring Failures" under the prior edition, same slot number, renamed), which specifically calls out "auditable events... are not logged" and "logs of applications... are not monitored for suspicious activity" as its defining examples — both apply here.

---

## 3. The fix

### 3a. Structured application-level audit log (immediate, no schema change)

Add a dedicated audit logger to `PortfolioService`, and log both outcomes — success and rejection — with full structured context, at **`INFO`**, not `DEBUG`. This matters: `application.yml` currently sets `logging.level.com.mbd: DEBUG` globally (Issue #8's finding). If that gets fixed to a sane production level (`INFO`/`WARN`) as it should, any audit line logged at `DEBUG` would silently stop appearing the moment Issue #8 is fixed — the two fixes would quietly cancel each other out unless the audit log is deliberately placed above whatever the baseline production level ends up being.

```kotlin
package com.mbd.portfolio.service

import org.slf4j.LoggerFactory
import org.slf4j.MDC

private val auditLog = LoggerFactory.getLogger("com.mbd.portfolio.audit")

@Service
class PortfolioService(/* ... */) {

    @Transactional
    fun executeTrade(trade: TradeDto, userId: String? = null): HoldingDto {
        return try {
            val result = if (trade.type == "BUY") executeBuy(trade) else executeSell(trade)
            auditLog.info(
                "trade_executed accountId={} fundId={} type={} quantity={} userId={}",
                trade.accountId, trade.fundId, trade.type, trade.quantity, userId ?: "unknown"
            )
            result
        } catch (ex: Exception) {
            auditLog.warn(
                "trade_rejected accountId={} fundId={} type={} quantity={} userId={} reason={}",
                trade.accountId, trade.fundId, trade.type, trade.quantity, userId ?: "unknown", ex.message
            )
            throw ex
        }
    }
    ...
}
```

Wrapping the existing dispatch (rather than adding log calls inside `executeBuy`/`executeSell` individually) keeps this future-proof against the [Issue #4](./Issue-4-Race-Condition-Fix-Analysis.md) fix, which changes exactly how a rejection is detected (an `IllegalStateException` from a `0`-rows-updated atomic `UPDATE` instead of a pre-check) — the audit wrapper doesn't care which branch produced the exception, only that one did.

The `userId` parameter is shown as optional/nullable deliberately: this fix has value **independent of** Issues #2/#3 landing — even with `userId` always `"unknown"` today, "an unattributed trade for account 42 for 5 units of fund 3 succeeded at 14:02:07" is a large improvement over no record at all. Once Issue #2/#3 add a resolved caller identity at the controller layer, threading it through here (or via SLF4J's `MDC`, populated once per request in a filter) closes the "who" gap without changing this method's shape again.

### 3b. Persisted, append-only trade history table (compliance-grade)

Log files rotate, get shipped to aggregators with retention limits, or get lost if a pod is evicted before shipping completes — acceptable for operational debugging, not for anything that might need to survive a regulator or an internal dispute months later (the source report's own compliance table cites SOX/MiFID II, both of which expect durable transaction records). A small Flyway migration closes that gap:

`V3__Create_Trade_Audit_Log_Table.sql`:

```sql
CREATE TABLE trade_audit_log (
    id BIGSERIAL PRIMARY KEY,
    account_id BIGINT NOT NULL,
    fund_id BIGINT NOT NULL,
    user_id VARCHAR(255),
    trade_type VARCHAR(10) NOT NULL,
    quantity DECIMAL(19, 4) NOT NULL,
    price DECIMAL(19, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,        -- EXECUTED | REJECTED
    reason VARCHAR(500),                -- populated when status = REJECTED
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_trade_audit_log_account_id ON trade_audit_log(account_id);
CREATE INDEX idx_trade_audit_log_created_at ON trade_audit_log(created_at);
```

```kotlin
@Entity
@Table(name = "trade_audit_log")
class TradeAuditLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null,
    @Column(name = "account_id") var accountId: Long,
    @Column(name = "fund_id") var fundId: Long,
    @Column(name = "user_id") var userId: String?,
    @Column(name = "trade_type") var tradeType: String,
    var quantity: BigDecimal,
    var price: BigDecimal,
    var status: String,
    var reason: String? = null,
    @Column(name = "created_at") var createdAt: LocalDateTime = LocalDateTime.now()
)

interface TradeAuditLogRepository : JpaRepository<TradeAuditLog, Long> {
    fun findByAccountIdOrderByCreatedAtDesc(accountId: Long): List<TradeAuditLog>
    // Deliberately no update/delete beyond what JpaRepository provides by default —
    // application code should only ever call save() to insert, never update an existing row.
}
```

Writing the row inside `executeTrade`'s existing `@Transactional` boundary means a persisted `EXECUTED` audit row and the corresponding `holdings` change commit or roll back together — they can't disagree with each other about whether a trade happened, at least for the local part of the transaction (see §5 for the boundary this doesn't cover). A `REJECTED` row can be written in the `catch` block of the wrapper in §3a, outside the rolled-back transaction (e.g., in `REQUIRES_NEW` propagation, or simply written via a non-transactional service call after the exception is caught at the controller), so a rejected attempt is still recorded even though nothing else about that request persists.

### Repository-level immutability (optional hardening)

For a closer approximation of a tamper-evident audit trail, restrict `TradeAuditLogRepository` to only expose `save()` (insert) and read methods — never expose or call `deleteById`/`delete` anywhere in application code, and optionally revoke `UPDATE`/`DELETE` grants on the `trade_audit_log` table for the application's database role at the Postgres level. This is a genuine hardening step worth doing, but it's additive polish, not required to close the finding as reported — the finding is "no audit logging exists," not "the audit log isn't tamper-proof."

---

## 4. Verifying the fix doesn't break anything

- **`PortfolioServiceTest`**: adding the audit logger (§3a) doesn't change `executeTrade`'s inputs/outputs, so no mock changes needed there. Adding `TradeAuditLogRepository` (§3b) as a new constructor dependency means the test's `@InjectMocks`/`@Mock` setup needs one more `@Mock private lateinit var tradeAuditLogRepository: TradeAuditLogRepository` — mechanical, no behavioral assertions change.
- **`PortfolioIntegrationTest`**: exercises the Kafka price-update consumer, not `executeTrade`, so it's unaffected either way.
- **API contract**: unchanged — `executeTrade`'s return value and thrown exceptions are identical; logging and persistence are pure side effects added around the existing logic.
- **Performance**: one additional `INSERT` per trade, inside a transaction that's already writing to `holdings` — negligible relative to the existing Feign calls to `account-service`/`fund-service` in the same request.

---

## 5. What this fix does *not* cover

Same boundary already disclosed in the [Issue #4 analysis](./Issue-4-Race-Condition-Fix-Analysis.md): `executeBuy`/`executeSell` call out to `account-service` (which commits its own balance change immediately) before this service's local transaction — now including the audit row — commits. If the local transaction later rolls back for an unrelated reason, the remote debit/credit has already happened but the local `EXECUTED` audit row never gets written; the trade briefly exists in the real world with no record of it here. Closing that fully needs the same saga/outbox-style work flagged as future architecture in the Issue #4 document — it's not something a logging fix can independently solve, since it's the same distributed-transaction limitation showing up in a second place.

---

## Summary

| Question | Answer |
|---|---|
| Fixable in current codebase? | **Yes** |
| Requires code changes? | Yes — an audit logger + wrapper around `executeTrade` (§3a); optionally a Flyway migration + entity + repository for a persisted trail (§3b) |
| Requires new infrastructure? | No |
| Current state | Zero record of any successful trade, anywhere — not in logs, not in any table. Failed trades are logged today, but only generically (no trade context, no actor) via `GlobalExceptionHandler` |
| OWASP Category | A09:2025 – Security Logging and Alerting Failures |
| Key design note | Log at `INFO` via a named audit logger, not `DEBUG` — otherwise fixing [Issue #8](./Three-Way-Security-Tool-Comparison.md) (global `DEBUG` logging) silently deletes this fix's output |
| Breaks existing tests? | No — mechanical mock addition only if §3b's repository is introduced |
| Fully closes the finding? | Closes the "no audit logging" gap for the trade endpoint. Does not close the pre-existing distributed-transaction atomicity gap from Issue #4 — a rolled-back local transaction after a successful remote debit still leaves no local audit trace, same limitation, not a new one |
| Depends on | [Issue #2](./Issue-2-No-Authentication-Fix-Analysis.md)/[Issue #3](./Issue-3-No-Authorization-Fix-Analysis.md) only for the "who" field being a real identity rather than `"unknown"` — the rest of the fix stands on its own |
