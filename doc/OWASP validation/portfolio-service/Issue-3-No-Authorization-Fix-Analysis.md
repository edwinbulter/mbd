# Fix Feasibility Analysis: Issue #3 — No Authorization Checks

**Date**: 2026-09-20
**Source finding**: `Three-Way-Security-Tool-Comparison.md`, "Issue #3: No Authorization Checks (CRITICAL)"
**Question answered**: Can this be fixed in the current codebase, and how?
**Depends on**: [`Issue-2-No-Authentication-Fix-Analysis.md`](./Issue-2-No-Authentication-Fix-Analysis.md) — authorization needs a validated identity to check *against*, so that fix must land first (or alongside).

---

## Verdict

**Yes — fixable, and `account-service` already contains the exact ownership-check pattern to replicate.** Unlike Issue #1 and #2, this one isn't a config change: it needs new logic in `portfolio-service` to resolve "who is calling" → "which account(s) do they own" → "does the requested `accountId` belong to them." All three pieces of that chain already exist elsewhere in the codebase; they're just not wired up in `portfolio-service`.

One important twist found while tracing this: **`account-service` already enforces ownership checks — but `portfolio-service`'s Feign client to it never sends the caller's identity, so that enforcement is silently bypassed for every call `portfolio-service` makes.** That's the first thing worth understanding before picking a fix.

---

## 1. Confirming the current state

`PortfolioController` and `PortfolioService` take `accountId` (as a path variable or inside `TradeDto`) and use it directly with zero ownership validation:

```kotlin
@GetMapping("/{accountId}")
fun getPortfolio(@PathVariable accountId: Long): ResponseEntity<PortfolioDto> {
    val portfolio = portfolioService.getPortfolio(accountId)   // no check that caller owns accountId
    ...
}

@PostMapping("/trade")
fun executeTrade(@RequestBody trade: TradeDto): ResponseEntity<HoldingDto> {
    ...
    val holding = portfolioService.executeTrade(trade)  // trade.accountId never validated against caller
    ...
}
```

`getPortfolioHistory` is worse than the other two: it doesn't even call out to `account-service` to check the account *exists* — it reads straight from `portfolio_value_history` by `accountId` with no cross-service check of any kind:

```kotlin
fun getPortfolioHistory(accountId: Long, limit: Int = 50): List<PortfolioValueSnapshotDto> {
    val snapshots = snapshotRepository.findByAccountIdOrderByTimestampDesc(accountId, PageRequest.of(0, limit))
    ...
}
```

Any caller who can reach the API (see Issue #2 for what "can reach" means today) can view or trade against **any** account by ID.

---

## 2. The bypass that makes this worse than it looks: Feign calls don't forward the caller's identity

`account-service/AccountController.kt` already has real ownership checks — this is not a hypothetical pattern, it's live code:

```kotlin
@GetMapping("/{accountId}")
fun getAccount(
    @PathVariable accountId: Long,
    @RequestHeader(value = "Authorization", required = false) authHeader: String?
): ResponseEntity<AccountDto> {
    val user = authHeader?.let { userClient.getUserProfile(it) }
    val account = accountRepository.findById(accountId).orElse(null) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, ...)
    if (user != null && account.userId != user.id) {
        throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to this account")
    }
    return ResponseEntity.ok(toDto(account))
}
```

Note the `required = false` and `if (user != null ...)` — the check is **opt-in per caller**: it only runs if an `Authorization` header is present. This is deliberately permissive so that legitimate service-to-service calls (with no end-user context) still work. But it means the check silently does nothing for any caller that doesn't send the header — and `portfolio-service`'s Feign client is exactly that caller:

```kotlin
// portfolio-service/client/AccountClient.kt
@FeignClient(name = "account-service", url = "http://account-service.mbd.svc.cluster.local:8080")
interface AccountClient {
    @GetMapping("/api/accounts/{accountId}")
    fun getAccount(@PathVariable accountId: Long): AccountDto?     // no Authorization header parameter at all

    @PostMapping("/api/accounts/{accountId}/deposit")
    fun updateBalance(@PathVariable accountId: Long, @RequestBody deposit: DepositDto): AccountDto?
}
```

So `account-service`'s own ownership check can never fire for anything `portfolio-service` does — every trade's balance debit/credit and every portfolio read goes through `account-service` with no `Authorization` header, which `account-service` correctly (by its own permissive design) treats as a trusted internal caller. The authorization gap has to be closed **in `portfolio-service` itself**; it cannot be inherited from downstream.

---

## 3. Why this needs more than a one-line check: the account isn't keyed by the JWT's identity

The JWT's `sub` claim is the **Keycloak ID** (a UUID-like string). `Account.userId` (and `AccountDto.userId`) is the **`user-service` internal numeric ID** (`users.id`) — a different id space, related by `users.keycloak_id`. There is no direct way to compare "JWT subject" to "account owner" without a lookup in between. This mapping already has a resolved path in the codebase, used by `account-service` today:

```
JWT (sub = keycloak UUID)
   │  UserClient.getUserProfile(authHeader)  → GET user-service /api/users/profile
   ▼
UserDto.id   (user-service internal numeric id)
   │  compare against
   ▼
AccountDto.userId   (from AccountClient.getAccount(accountId))
```

`portfolio-service` needs the same two-hop resolution: JWT → `user-service` (id) → compare against `account-service`'s `AccountDto.userId`. Both DTOs (`UserDto.id`, `AccountDto.userId`) already carry the fields needed; `portfolio-service` just doesn't have a `UserClient` yet (only `AccountClient` and `FundClient`).

---

## 4. The fix

### 4a. Add a `UserClient` to `portfolio-service` (copy of `account-service`'s)

`portfolio-service/src/main/kotlin/com/mbd/portfolio/client/UserClient.kt` (new file):

```kotlin
package com.mbd.portfolio.client

import com.mbd.shared.dto.UserDto
import org.springframework.cloud.openfeign.FeignClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader

@FeignClient(name = "user-service", url = "http://user-service.mbd.svc.cluster.local:8080")
interface UserClient {
    @GetMapping("/api/users/profile")
    fun getUserProfile(@RequestHeader("Authorization") authHeader: String): UserDto?
}
```

Byte-for-byte the same interface `account-service` already uses successfully.

### 4b. Add an ownership check at the controller boundary

Following `account-service`'s own precedent (its authorization checks live in the controller, not a service layer — it has no separate service class), add the check in `PortfolioController`, once [Issue #2](./Issue-2-No-Authentication-Fix-Analysis.md)'s Spring Security setup is in place so `Authorization` is guaranteed present and already signature-validated:

```kotlin
@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(
    private val portfolioService: PortfolioService,
    private val userClient: UserClient,
    private val accountClient: AccountClient   // already exists in PortfolioService; also inject here for the check
) {
    @GetMapping("/{accountId}")
    fun getPortfolio(
        @PathVariable accountId: Long,
        @RequestHeader("Authorization") authHeader: String
    ): ResponseEntity<PortfolioDto> {
        requireOwnership(accountId, authHeader)
        return ResponseEntity.ok(portfolioService.getPortfolio(accountId))
    }

    @GetMapping("/{accountId}/history")
    fun getPortfolioHistory(
        @PathVariable accountId: Long,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestHeader("Authorization") authHeader: String
    ): ResponseEntity<List<PortfolioValueSnapshotDto>> {
        requireOwnership(accountId, authHeader)
        return ResponseEntity.ok(portfolioService.getPortfolioHistory(accountId, limit))
    }

    @PostMapping("/trade")
    fun executeTrade(
        @RequestBody trade: TradeDto,
        @RequestHeader("Authorization") authHeader: String
    ): ResponseEntity<HoldingDto> {
        requireOwnership(trade.accountId, authHeader)
        // ...existing quantity validation, then portfolioService.executeTrade(trade)
    }

    private fun requireOwnership(accountId: Long, authHeader: String) {
        val user = userClient.getUserProfile(authHeader)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication")
        val account = accountClient.getAccount(accountId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found")
        if (account.userId != user.id) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied to this account")
        }
    }
}
```

This mirrors `account-service`'s check exactly (`account.userId != user.id` → 403), just centralized into one helper instead of duplicated per-endpoint the way `account-service` currently repeats it four times — a small improvement, not a deviation from the established pattern.

Note `getPortfolioHistory` gains an existence/ownership check it never had before (§1) — previously it would silently return an empty list for a non-existent account and full history for any account when called by anyone; now it 403s/404s appropriately.

### Why not just forward the `Authorization` header to `account-service` and let it enforce ownership?

That was considered and rejected as the primary fix, for two reasons:
1. **`getPortfolioHistory` never calls `account-service` at all** — it reads local snapshot data directly, so there's no downstream call to attach a header to. The check has to exist in `portfolio-service` regardless.
2. **Feign surfaces a downstream 403 as an uncaught `FeignException`**, which `GlobalExceptionHandler`'s generic `Exception` handler turns into a `500 Internal Server Error` (there's no `FeignException`/`ErrorDecoder` handling anywhere in the codebase — checked). The *access* would still be denied (no data leak), but with the wrong status code and no clear reason — worse API behavior than an explicit check. An explicit `requireOwnership()` returns a correct, intentional `403`.

Propagating the header is still worth doing for `getAccount`/`updateBalance` as defense-in-depth once `portfolio-service` does its own check (so `account-service` isn't relying solely on upstream trust either), but it's a secondary hardening step, not a substitute for the explicit check.

---

## 5. Verifying the fix doesn't break anything

- **`PortfolioServiceTest`** (unit tests, mocks `AccountClient`/`FundClient` directly against `PortfolioService`) is unaffected — the ownership check is proposed at the controller layer, not inside `PortfolioService`, so its method signatures and behavior are unchanged.
- **`PortfolioIntegrationTest`** drives the Kafka consumer and repositories directly, never through `PortfolioController`, so it's unaffected.
- **No existing `PortfolioController` test exists** to update; a new one should be added alongside this fix (e.g. `MockMvc` or `@SpringBootTest` cases for: owner succeeds, non-owner gets 403, unknown account gets 404, missing/invalid JWT gets 401 once Issue #2 lands).
- **Frontend impact**: none. The customer frontend already only ever requests the logged-in user's own account (the dashboard resolves `accountId` from the authenticated session), so legitimate traffic is unaffected — only requests for *other* accounts start being rejected, which is the point of the fix.
- **Extra network hops**: `requireOwnership` adds one `UserClient` call and reuses the existing `AccountClient.getAccount` call (already made inside `executeBuy`/`executeSell`, so for those paths it's not even a net-new call — the ownership check can reuse that fetched `AccountDto` rather than calling `accountClient.getAccount` twice per trade). `getPortfolio`/`getPortfolioHistory` gain one new `AccountClient` call each, consistent with the per-request Feign call volume `PortfolioService.getPortfolio` already has (it calls `fundClient.getFund` once per holding).

---

## 6. Sequencing with the other issues

1. **Issue #2 must land first (or alongside)**: `requireOwnership` depends on having a trustworthy, signature-verified `Authorization` header/JWT to resolve identity from. Doing this fix before Issue #2 would mean checking ownership against an unverified/forgeable token — authorization without authentication isn't meaningful.
2. **This fix should be replicated for `account-service`'s own Feign-originated blind spot** (§2): once `portfolio-service` forwards the real caller identity to `account-service` as defense-in-depth, `account-service`'s existing `if (user != null ...)` checks start actually applying to `portfolio-service`-originated calls too, not just direct external calls.
3. **`fund-service`** has no ownership concept (funds aren't user-owned), so it's out of scope for this specific finding.

---

## Summary

| Question | Answer |
|---|---|
| Fixable in current codebase? | **Yes** |
| Requires code changes? | Yes — 1 new `UserClient` Feign interface (copied from `account-service`) + one ownership-check helper wired into 3 controller methods |
| Requires new infrastructure? | No |
| Pattern already proven in this codebase? | Yes — `account-service/AccountController` implements the identical `account.userId != user.id` check today |
| Key discovery | `account-service`'s existing ownership checks are silently bypassed for all `portfolio-service`-originated calls, because `portfolio-service`'s `AccountClient` never forwards the caller's `Authorization` header — the fix can't be "let the downstream service handle it," it must be explicit in `portfolio-service` |
| Breaks existing tests? | No — `PortfolioServiceTest` and `PortfolioIntegrationTest` don't touch the controller layer; a new controller test should be added, not an existing one fixed |
| Depends on | [Issue #2](./Issue-2-No-Authentication-Fix-Analysis.md) (needs a verified identity to check against) |
