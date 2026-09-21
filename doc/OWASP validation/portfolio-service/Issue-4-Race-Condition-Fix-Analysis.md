# Fix Feasibility Analysis: Issue #4 — Race Condition in Distributed Transaction

**Date**: 2026-09-20
**Source finding**: `Three-Way-Security-Tool-Comparison.md`, "Issue #4: Race Condition in Distributed Transaction (HIGH)"
**Question answered**: Can this be fixed in the current codebase, and how?
**Independent of**: Issues #2/#3 — this is a data-integrity bug, not an access-control gap; it can be fixed regardless of authentication/authorization status.

---

## Background: What Is a TOCTOU Race Condition?

**TOCTOU** stands for **Time-Of-Check to Time-Of-Use**. It describes a bug shape where code:

1. **Checks** some condition ("is there enough money in this account?"), then
2. **Acts** on the assumption that the condition still holds ("go ahead and spend it"),

with a **gap in between** where the checked state can change before the action happens. If two (or more) callers run this check-then-act sequence concurrently, both can see the condition as true *at the time they checked it*, both proceed to act, and the combined effect violates an invariant that each caller individually thought it was upholding — e.g., two withdrawals that each individually looked affordable together overdraw the account, even though each caller "correctly" verified sufficient funds moments before spending.

The defining feature of a TOCTOU bug is that **the check and the act are not a single atomic operation**. Anything that can run between them — another thread, another request, another process, or (in a distributed system) another service entirely — can invalidate the check before the act happens, and neither caller has any way to know the other one intervened.

### How Issue #4's race condition can happen

Walk through `PortfolioService.executeBuy` with the account starting at a real balance of **€1,000**, and two `BUY` trades arriving at nearly the same time, each costing **€700**:

| Time | Request A (BUY, costs €700) | Request B (BUY, costs €700) | Account balance (in `account-service`) |
|---|---|---|---|
| t0 | — | — | €1,000 |
| t1 | `accountClient.getAccount(1)` → reads balance **€1,000** | | €1,000 |
| t2 | | `accountClient.getAccount(1)` → also reads balance **€1,000** | €1,000 |
| t3 | Checks `1000 < 700`? No → check **passes** | | €1,000 |
| t4 | | Checks `1000 < 700`? No → check **passes** | €1,000 |
| t5 | `accountClient.updateBalance(1, -700)` → `account-service` applies the debit | | €300 |
| t6 | | `accountClient.updateBalance(1, -700)` → `account-service` applies *its* debit too, against whatever the balance now is | **-€400** |

Both requests independently verified "yes, €700 is affordable" against a balance of €1,000 — each check was individually correct *at the moment it ran*. But by the time both debits actually apply, the account has been charged €1,400 against a starting balance of €1,000, landing at **-€400**. Neither request did anything individually wrong; the bug is that the check (t1/t2) and the act (t5/t6) are two separate HTTP calls with an unguarded window between them, and nothing prevents a second request from threading through that window using the same stale "€1,000 is available" assumption.

This is exactly the classic TOCTOU pattern, just stretched across a network call instead of within a single process: `account.balance < totalCost` is the **check**, `accountClient.updateBalance(...)` is the **use**, and the gap between them is wide enough (a full HTTP round-trip, plus whatever the JVM's thread scheduler or network happens to interleave) for a second, concurrent trade to slip through. The same shape reproduces on the `SELL` side against `holding.quantity` (§1c below) without even needing a network hop — two threads inside `portfolio-service` alone can race the same way against its local database.

---

## Verdict

**Mostly yes.** The specific race the finding calls out — the balance check happening in a separate HTTP round-trip from the balance debit, letting two concurrent trades both pass the check and jointly overdraw an account — is **fully fixable without any distributed locking, sagas, or new infrastructure**, by replacing the check-then-act pattern with a single atomic conditional `UPDATE` at the database that owns the balance. Tracing the code further up turns up **two more races of the same shape that the original finding didn't call out** (one of them is arguably worse than the one that was flagged), both fixable the same way.

One part of the underlying problem is **not** fixable with a small change: if the local database write in `portfolio-service` fails *after* the remote debit in `account-service` already committed, there's no compensating action today, and closing that gap needs a genuine architectural pattern (saga/outbox), not a code tweak. That part is called out honestly in §5 rather than glossed over.

---

## 1. Confirming the current state — and finding it's broader than described

### 1a. The race as described in the finding (BUY path, cross-service)

`PortfolioService.executeBuy`:

```kotlin
val account = accountClient.getAccount(trade.accountId) ?: throw IllegalArgumentException("Account not found")
if (account.balance < totalCost) {
    throw IllegalStateException("Insufficient balance")
}
accountClient.updateBalance(trade.accountId, DepositDto(totalCost.negate()))  // TOCTOU gap
```

Two concurrent `BUY` requests for the same account can both call `getAccount` before either calls `updateBalance`, both read the same starting balance, both pass the `balance < totalCost` check, and both proceed to debit — overdrawing the account by up to the smaller of the two trade amounts. Confirmed accurate.

### 1b. A second, more fundamental gap in the same code path — `account-service` never enforces non-negative balance at all

`AccountController.deposit` (the endpoint `updateBalance` calls) does a plain read-modify-write with **no floor check on the resulting balance**:

```kotlin
account.balance = account.balance.add(request.amount)   // request.amount is negative for a debit
account.updatedAt = LocalDateTime.now()
val savedAccount = accountRepository.save(account)
```

It validates the debit/deposit is within `MIN_WITHDRAWAL_AMOUNT`/`MAX_WITHDRAWAL_AMOUNT` bounds, but never checks `account.balance + request.amount >= 0`. **The only place that ever refuses an overdraft is `portfolio-service`'s pre-check in §1a** — the service that actually owns the `balance` column enforces nothing. This means the race isn't just "two requests can slip past a check" — it's that the check exists in exactly one caller, as an advisory client-side guess, not as an invariant enforced where the data lives. Any other caller of `POST /api/accounts/{id}/deposit` (direct, or once Issue #2/#3 close off unauthenticated access, any other legitimate future feature) inherits this gap too.

### 1c. A third race, purely local to `portfolio-service` — oversell on the SELL path

`PortfolioService.executeSell`:

```kotlin
val holding = holdingRepository.findByAccountIdAndFundId(trade.accountId, trade.fundId) ?: throw ...
if (holding.quantity < trade.quantity) {
    throw IllegalStateException("Insufficient quantity to sell...")
}
accountClient.updateBalance(trade.accountId, DepositDto(proceeds))
val newQuantity = holding.quantity.subtract(trade.quantity)
// ...save or delete
```

This is the identical check-then-act shape as §1a, but entirely within `portfolio-service`'s own database — no cross-service call is even needed to reproduce it. `Holding` has no `@Version` column (checked — neither `Holding.kt` nor `Account.kt` declares one), `HoldingRepository` has no `@Lock`/pessimistic-read method, and `executeTrade`'s `@Transactional` only gives the *local* JPA calls read-committed isolation (PostgreSQL's default) — it does not serialize two separate transactions' reads of the same row. Two concurrent `SELL` requests against the same holding can both read the same `quantity`, both pass the check, and both subtract — allowing the account to sell more units of a fund than it actually holds (`holding.quantity` can go negative, since nothing clamps it either).

**Net finding**: the report flagged one instance of this bug shape; the codebase has (at least) three — one already correctly identified, one deeper invariant gap in the same call, and one entirely separate instance on the sell side.

---

## 2. The fix: replace check-then-act with an atomic conditional `UPDATE`

The standard, well-understood fix for "check a balance/quantity, then mutate it, with a race in between" is to make the check and the mutation **one atomic SQL statement**, so the database's own row-level locking does the serialization — no application-level distributed lock, no Redis/ZooKeeper, no saga needed for *this* part of the problem.

### Why these queries are atomic (and the original code wasn't)

Two things make `UPDATE ... SET balance = balance + :amount WHERE id = :id AND balance + :amount >= 0` a single atomic operation, in a way that `getAccount()` followed by `updateBalance()` never could be:

1. **It's one statement sent to the database, not a value round-tripped through the application.** The original code reads `balance` into the JVM (`account.balance`), makes the decision *in application memory* (`if (account.balance < totalCost)`), and only then sends a second, separate statement to write the new value. Between "read" and "write" there are two full network hops and an arbitrary amount of time during which literally anything else can run. The atomic version never pulls the balance out to decide anything — the decision (`balance + :amount >= 0`) is evaluated by the database engine itself, as part of executing the write, against whatever the row's value is *at that exact instant*. There is no intermediate state visible to the application, so there's no window for another request to act on a value that's about to become stale.

2. **The database serializes concurrent writers to the same row.** An `UPDATE` in PostgreSQL takes an exclusive row lock on every row it matches, held until the transaction commits or rolls back. If a second `UPDATE` targets the same row while that lock is held, it doesn't get to evaluate its own `WHERE` clause yet — it blocks, waiting for the lock. Only once the first transaction commits does the second one proceed, and when it does, it evaluates `balance + :amount >= 0` against the balance **as the first transaction left it**, never against the value that existed before the first write. This is the mechanism that turns two racing requests into two strictly ordered ones: whichever gets the lock second is guaranteed to see the first one's effect before it's allowed to check anything.

Put together: the check and the write can't be split apart by a second transaction, because there's only one statement (so nothing else can run *between* checking and writing) and the row lock means nothing else can even run *concurrently with* the checking-and-writing (so no second statement can act on a value the first statement is about to change). That's precisely what "atomic" means here — not that the operation is fast, but that no other transaction can observe or act on a partial, in-between state.

One more detail that matters for the Kotlin side: `@Modifying @Query` sends this UPDATE directly as a single SQL statement. It deliberately bypasses Hibernate's normal entity flow — `repository.findById()` load into the persistence context, mutate the field, `repository.save()` flush later — because *that* flow is itself a load-then-write split across two separate steps, i.e. exactly the same check-then-act shape as the original bug, just moved one layer down (into the ORM instead of the application code). A plain `save()` would not have fixed anything; the fix specifically depends on issuing one hand-written `UPDATE` so the check and the write can never be pulled apart.

### 2a. `account-service`: atomic, floor-enforcing balance adjustment

`AccountRepository.kt`:

```kotlin
@Repository
interface AccountRepository : JpaRepository<Account, Long> {
    fun findByUserId(userId: Long): List<Account>
    fun findByAccountNumber(accountNumber: String): Account?

    @Modifying
    @Query("""
        UPDATE Account a
        SET a.balance = a.balance + :amount, a.updatedAt = CURRENT_TIMESTAMP
        WHERE a.id = :id AND a.balance + :amount >= 0
    """)
    fun adjustBalanceIfSufficient(id: Long, amount: BigDecimal): Int   // 1 = applied, 0 = insufficient funds or not found
}
```

`AccountController.deposit`, replacing only the final mutation step (all the existing amount/limit validation stays exactly as-is):

```kotlin
val rowsUpdated = accountRepository.adjustBalanceIfSufficient(accountId, request.amount)
if (rowsUpdated == 0) {
    throw ResponseStatusException(HttpStatus.CONFLICT, "Insufficient balance")
}
val savedAccount = accountRepository.findById(accountId).get()
```

Applying the mechanism above: whichever of two concurrent debits reaches the row second waits for the first's row lock, then evaluates `a.balance + :amount >= 0` against the **post-first-debit** balance — not the stale €1,000 both requests would have read via a separate `SELECT` in the walkthrough earlier. It either succeeds against the now-lower balance or correctly gets `0` rows updated (insufficient funds); it can no longer succeed based on a shared stale read the way both requests did in §"How this race condition can happen".

### 2b. `portfolio-service`: atomic, floor-enforcing quantity decrement (SELL path)

`HoldingRepository.kt`:

```kotlin
@Modifying
@Query("""
    UPDATE Holding h
    SET h.quantity = h.quantity - :qty, h.updatedAt = CURRENT_TIMESTAMP
    WHERE h.accountId = :accountId AND h.fundId = :fundId AND h.quantity >= :qty
""")
fun decrementQuantityIfSufficient(accountId: Long, fundId: Long, qty: BigDecimal): Int
```

`PortfolioService.executeSell`:

```kotlin
private fun executeSell(trade: TradeDto): HoldingDto {
    val fund = fundClient.getFund(trade.fundId) ?: throw IllegalArgumentException("Fund not found")
    val rowsUpdated = holdingRepository.decrementQuantityIfSufficient(trade.accountId, trade.fundId, trade.quantity)
    if (rowsUpdated == 0) {
        throw IllegalStateException("Insufficient quantity to sell, or no holding found for this fund")
    }
    val proceeds = fund.currentPrice.multiply(trade.quantity)
    accountClient.updateBalance(trade.accountId, DepositDto(proceeds))

    val holding = holdingRepository.findByAccountIdAndFundId(trade.accountId, trade.fundId)!!
    if (holding.quantity.compareTo(BigDecimal.ZERO) == 0) {
        holdingRepository.delete(holding)
    } else {
        holding.currentValue = holding.quantity.multiply(fund.currentPrice)
        holdingRepository.save(holding)
    }
    return toDto(holding, fund.name, fund.isin)
}
```

The safety-critical part (can't oversell) is now enforced by the atomic `UPDATE`'s `WHERE ... AND h.quantity >= :qty` clause, exactly like §2a. The follow-up read-and-maybe-delete is purely housekeeping (deciding whether a zero-quantity row should be removed); a race there has no financial consequence — worst case a `quantity = 0` row lingers briefly.

### Why an atomic `UPDATE` and not a pessimistic lock (`SELECT ... FOR UPDATE`)?

This codebase's trade flow makes a synchronous Feign/HTTP call to another pod (`accountClient.updateBalance`) **from inside** the local `@Transactional` method. Using `SELECT ... FOR UPDATE` to lock the holding row would mean holding a Postgres row lock for the entire duration of that outbound HTTP call — tying up a DB connection and blocking every other trade on that holding while waiting on network I/O to a different service. That's a scalability/deadlock-risk anti-pattern in a microservices setting. The atomic-`UPDATE` approach never holds a lock across a network boundary: each statement is a single round-trip to its own database, so the fix is compatible with the existing architecture instead of fighting it.

---

## 3. Verifying the fix doesn't break anything

- **Existing business rules unchanged**: all of `AccountController.deposit`'s amount/limit validation (zero-amount check, min/max deposit, min/max withdrawal) runs exactly as before; only the final "read balance, mutate, save" step is replaced.
- **`PortfolioServiceTest`**: the BUY tests mock `accountClient.getAccount`/`updateBalance` and don't assert on `account-service`'s internal persistence mechanism, so they're unaffected. The SELL tests currently mock `holdingRepository.findByAccountIdAndFundId` + `save`/`delete`; they'd need to additionally mock the new `decrementQuantityIfSufficient` call — a mechanical test update, not a behavioral one (same inputs, same expected outputs).
- **`PortfolioIntegrationTest`** exercises the Kafka price-update consumer, not the trade endpoints, so it's unaffected.
- **API contract**: `executeBuy`'s error message ("Insufficient balance") is preserved by translating `account-service`'s new `409` into the same `IllegalStateException` client-side (or portfolio-service can inspect the Feign response — either way the user-visible behavior is unchanged, just now correctly enforced instead of advisory).

---

## 4. What this fix does *not* cover (and why that part is genuinely harder)

Even after §2's fix, one gap remains that **cannot** be closed with a small, local change: `executeBuy`/`executeSell` call `accountClient.updateBalance` (which commits immediately in `account-service`'s own database) *before* `holdingRepository.save`/the atomic decrement commits in `portfolio-service`'s database. If the local write then fails for an unrelated reason (a DB blip, a constraint violation, the pod being killed mid-request), `@Transactional` rolls back `portfolio-service`'s own writes — but it has no way to undo the already-committed remote debit/credit in `account-service`. The customer's cash moves with no corresponding holding change, or vice versa.

This is not the race condition the finding described (that one is now closed by §2); it's the general **distributed-transaction atomicity problem** inherent to any design where a saga spans two independently-committing databases without a compensating-action mechanism. Genuinely fixing it needs one of:
- A **saga pattern**: emit a `TradeInitiated` event, have each service perform its local step and emit success/failure, with a defined compensating action (e.g., a reversing deposit) if a later step fails.
- An **outbox pattern**: write the intended balance change and the intended holding change as a single local transaction (e.g., in `portfolio-service` only), then have a reliable relay apply the account-side effect asynchronously with retries — trading immediate consistency for eventual consistency plus auditability.
- Two-phase commit across services — practically unused in modern microservice systems for good reasons (availability, coupling), not a realistic recommendation here.

Any of these is a real architectural addition (new event types, new failure-handling code, new operational surface), not a config change or a query rewrite. It's honest to say this part of "the race condition problem" stays open after the minimal fix — worth tracking as a separate, larger follow-up rather than folding it into this finding's remediation.

---

## 5. Summary

| Question | Answer |
|---|---|
| Fixable in current codebase? | **Yes, for the check-then-act race as described** — and for two related instances of the same bug shape found while tracing the code |
| Requires code changes? | Yes — one `@Modifying @Query` method in `AccountRepository` + a small edit to `AccountController.deposit`; one equivalent method in `HoldingRepository` + a small edit to `PortfolioService.executeSell` |
| Requires new infrastructure? | No — plain SQL-level atomicity via row locking, already available in PostgreSQL |
| Requires distributed locking? | No, and deliberately avoided — a lock held across the outbound HTTP call to `account-service` would be worse than the current design |
| Additional gap found beyond the reported one | `account-service` never enforced non-negative balance itself — only `portfolio-service`'s (racy) pre-check did; and the identical bug shape exists independently on the `SELL`/holding-quantity path |
| Fully closes the finding? | Closes the **overdraft/oversell race** completely. Does **not** close the separate, harder problem of no compensating action if the local write fails after the remote call already committed — that needs a saga/outbox pattern, flagged as future architectural work, not a minimal fix |
| Breaks existing tests? | Mechanical updates only — `PortfolioServiceTest`'s SELL-path mocks need to mock the new repository method; no test assertions change |
