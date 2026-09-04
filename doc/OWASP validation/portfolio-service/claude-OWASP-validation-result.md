# OWASP Top 10 Security Validation Report
## Portfolio Service

**Service**: portfolio-service
**Date**: 2026-09-04
**Reviewer**: Security Analysis (Manual Code Review)
**Scope**: backend/portfolio-service

---

## Executive Summary

**Overall Risk Level**: 🔴 **HIGH**

- **Critical Issues**: 1
- **High Issues**: 2
- **Medium Issues**: 2
- **Low Issues**: 1
- **Informational**: 3

The portfolio-service has **critical security vulnerabilities** that require immediate attention, particularly around authentication/authorization and Kafka deserialization security.

---

## OWASP Top 10 (2021) Analysis

### A01:2021 - Broken Access Control ⚠️ **HIGH RISK**

**Status**: 🔴 **VULNERABLE**

#### Findings:

1. **No Authentication on API Endpoints** - `PortfolioController.kt`
   - **Severity**: HIGH
   - **Location**: All endpoints in `PortfolioController` (lines 24-65)
   - **Issue**: No authentication or authorization checks on any endpoints
   - **Impact**: Any unauthenticated user can:
     - View any account's portfolio (`GET /api/portfolio/{accountId}`)
     - View portfolio history for any account
     - Execute trades on behalf of any account (`POST /api/portfolio/trade`)
   - **Evidence**:
     ```kotlin
     @GetMapping("/{accountId}")
     fun getPortfolio(@PathVariable accountId: Long): ResponseEntity<PortfolioDto> {
         // No authentication check
         val portfolio = portfolioService.getPortfolio(accountId)
         return ResponseEntity.ok(portfolio)
     }
     ```

2. **No Authorization on Trade Execution** - `PortfolioController.kt:39`
   - **Severity**: HIGH
   - **Issue**: Any user can execute trades for any account by simply knowing the account ID
   - **Impact**: Unauthorized buying/selling of securities, financial fraud
   - **CWE**: CWE-862 (Missing Authorization)

#### Recommendations:
- Implement Spring Security OAuth2 Resource Server
- Validate JWT tokens on all endpoints
- Ensure authenticated user owns the account before allowing operations
- Add `@PreAuthorize` annotations to enforce ownership checks

---

### A02:2021 - Cryptographic Failures ✅ **PASS**

**Status**: ✅ **SECURE**

#### Findings:
- Database password stored in environment variable (`${DB_PASSWORD}`) - ✅ Good practice
- No hardcoded credentials found
- TLS handled by Istio service mesh (mTLS)
- No sensitive data encryption issues identified

---

### A03:2021 - Injection ✅ **PASS**

**Status**: ✅ **SECURE**

#### Findings:
- Uses Spring Data JPA repositories with parameterized queries
- No raw SQL or HQL found
- Repository methods use type-safe query methods:
  ```kotlin
  holdingRepository.findByAccountId(accountId)
  holdingRepository.findByFundId(fundId)
  ```
- Path variables properly typed as `Long` (lines 25, 32, 40)
- No command injection vectors identified

---

### A04:2021 - Insecure Design ⚠️ **MEDIUM RISK**

**Status**: ⚠️ **NEEDS IMPROVEMENT**

#### Findings:

1. **Race Condition in Trade Execution** - `PortfolioService.kt:48-81`
   - **Severity**: MEDIUM
   - **Issue**: Balance check and debit are separate operations, not atomic
   - **Location**: Lines 52-57
   - **Code**:
     ```kotlin
     val account = accountClient.getAccount(trade.accountId)
     if (account.balance < totalCost) {
         throw IllegalStateException("Insufficient balance")
     }
     accountClient.updateBalance(trade.accountId, DepositDto(totalCost.negate()))
     ```
   - **Impact**: Time-of-check to time-of-use (TOCTOU) vulnerability; concurrent trades could overdraw account
   - **CWE**: CWE-367 (Time-of-check Time-of-use Race Condition)

2. **No Idempotency for Trade Operations**
   - **Severity**: MEDIUM
   - **Issue**: No transaction ID or idempotency key for trade requests
   - **Impact**: Network retries could execute duplicate trades

#### Recommendations:
- Implement distributed locking (e.g., Redis lock) for account balance operations
- Add trade request IDs for idempotency
- Consider implementing saga pattern for distributed transactions

---

### A05:2021 - Security Misconfiguration ⚠️ **HIGH RISK**

**Status**: 🔴 **VULNERABLE**

#### Findings:

1. **Overly Permissive Actuator Endpoints** - `application.yml:37`
   - **Severity**: MEDIUM
   - **Location**: `application.yml` lines 36-37
   - **Issue**: Actuator endpoints exposed without authentication
   - **Code**:
     ```yaml
     management:
       endpoints:
         web:
           exposure:
             include: health,info,metrics,prometheus
     ```
   - **Impact**: Information disclosure (heap dumps, environment variables, metrics)

2. **Debug Logging in Production** - `application.yml:56`
   - **Severity**: LOW
   - **Issue**: DEBUG level logging enabled for application code
   - **Code**:
     ```yaml
     logging:
       level:
         com.mbd: DEBUG
     ```
   - **Impact**: Sensitive data may be logged (account IDs, balances, trades)

3. **Health Endpoint Shows Details** - `application.yml:40`
   - **Severity**: LOW
   - **Code**: `show-details: always`
   - **Impact**: Internal system details exposed

#### Recommendations:
- Secure actuator endpoints with authentication
- Use INFO or WARN level logging in production
- Show health details only when authenticated

---

### A06:2021 - Vulnerable and Outdated Components ℹ️ **INFO**

**Status**: ℹ️ **REVIEW REQUIRED**

#### Findings:
- Spring Boot 3.1 used (check for latest patches)
- Kotlin 1.9 used (check for latest version)
- No known vulnerable dependencies identified in code review
- **Recommendation**: Run `./gradlew dependencyCheckAnalyze` to scan for CVEs

---

### A07:2021 - Identification and Authentication Failures 🔴 **CRITICAL**

**Status**: 🔴 **CRITICAL**

#### Findings:

1. **No Authentication Mechanism** - `PortfolioController.kt`
   - **Severity**: CRITICAL
   - **Issue**: Service has no Spring Security configuration
   - **Impact**: Complete authentication bypass
   - **Affected Endpoints**:
     - `GET /api/portfolio/{accountId}` - No auth
     - `GET /api/portfolio/{accountId}/history` - No auth
     - `POST /api/portfolio/trade` - No auth (can execute trades anonymously!)

2. **Relies on Edge Authentication Only**
   - **Severity**: HIGH
   - **Issue**: Defense-in-depth violation; trusts Istio/gateway entirely
   - **Impact**: If gateway is bypassed, service is completely exposed

#### Recommendations:
- **URGENT**: Implement Spring Security OAuth2 Resource Server
- Validate JWT tokens at application level
- Never trust edge authentication alone (defense-in-depth)

---

### A08:2021 - Software and Data Integrity Failures 🔴 **CRITICAL**

**Status**: 🔴 **CRITICAL VULNERABILITY**

#### Findings:

1. **Unsafe Kafka Deserialization** - `application.yml:31`
   - **Severity**: CRITICAL
   - **Location**: `application.yml` line 31
   - **Issue**: Wildcard trusted packages for JSON deserialization
   - **Code**:
     ```yaml
     consumer:
       properties:
         spring.json.trusted.packages: "*"
     ```
   - **Impact**:
     - Remote Code Execution (RCE) via malicious Kafka messages
     - Attacker can send serialized Java objects to `fund-price-updates` topic
     - Could lead to complete system compromise
   - **CWE**: CWE-502 (Deserialization of Untrusted Data)
   - **CVSS Estimate**: 9.8 (Critical)

2. **No Message Validation** - `FundPriceUpdateConsumer.kt:20`
   - **Severity**: MEDIUM
   - **Issue**: No validation of `FundPriceUpdate` message content
   - **Code**:
     ```kotlin
     fun handlePriceUpdate(update: FundPriceUpdate) {
         // No validation of update.fundId, update.newPrice, etc.
         val holdings = holdingRepository.findByFundId(update.fundId)
     ```
   - **Impact**: Malicious messages could corrupt portfolio values

#### Recommendations:
- **URGENT**: Change `spring.json.trusted.packages` to specific package: `com.mbd.shared.dto`
- Add input validation on Kafka messages (schema validation)
- Implement message signing/verification
- Consider using Avro schema registry for stronger type safety

---

### A09:2021 - Security Logging and Monitoring Failures ⚠️ **MEDIUM RISK**

**Status**: ⚠️ **NEEDS IMPROVEMENT**

#### Findings:

1. **Exception Stack Traces Logged** - `GlobalExceptionHandler.kt:54`
   - **Severity**: LOW
   - **Issue**: Full stack traces logged on errors
   - **Code**:
     ```kotlin
     logger.error("Unexpected exception occurred", ex)
     ```
   - **Impact**: Could log sensitive data (account IDs, balances, internal paths)

2. **No Audit Logging for Financial Transactions**
   - **Severity**: MEDIUM
   - **Issue**: Trade executions not logged for audit trail
   - **Location**: `PortfolioService.kt:38-106`
   - **Impact**:
     - Cannot trace who executed trades
     - Regulatory compliance issues (MiFID II, SOX)
     - Difficult forensics after security incidents

3. **No Security Event Logging**
   - **Severity**: MEDIUM
   - **Issue**: No logging for:
     - Failed authorization attempts
     - Suspicious activity (rapid trades, unusual patterns)
     - Kafka deserialization errors

#### Recommendations:
- Implement audit logging for all trades with user identity, timestamp, amounts
- Add security event logging (failed auth, rate limiting, etc.)
- Sanitize logs to remove sensitive data
- Send audit logs to SIEM system

---

### A10:2021 - Server-Side Request Forgery (SSRF) ✅ **ACCEPTABLE**

**Status**: ✅ **ACCEPTABLE RISK**

#### Findings:
- Feign clients call internal services via hardcoded cluster DNS:
  - `http://account-service.mbd.svc.cluster.local:8080`
  - `http://fund-service.mbd.svc.cluster.local:8080`
- **Risk Assessment**: ACCEPTABLE
  - URLs are hardcoded, not user-controlled
  - Internal service mesh calls (Istio mTLS)
  - No external URL parameters accepted

---

## Additional Security Concerns

### Business Logic Vulnerabilities

1. **Integer Overflow in Price Calculation** - `PortfolioService.kt:50,92`
   - **Severity**: LOW
   - **Issue**: No overflow protection on `BigDecimal.multiply()` operations
   - **Code**: `val totalCost = fund.currentPrice.multiply(trade.quantity)`
   - **Impact**: Extremely large quantities could cause precision loss

2. **No Rate Limiting**
   - **Severity**: MEDIUM
   - **Issue**: No rate limiting on trade endpoint
   - **Impact**: API abuse, flash crash scenarios

---

## Summary of Findings

| OWASP Category | Status | Risk Level | Critical Issues |
|----------------|--------|------------|-----------------|
| A01: Broken Access Control | 🔴 Fail | HIGH | 2 |
| A02: Cryptographic Failures | ✅ Pass | NONE | 0 |
| A03: Injection | ✅ Pass | NONE | 0 |
| A04: Insecure Design | ⚠️ Review | MEDIUM | 0 |
| A05: Security Misconfiguration | ⚠️ Review | MEDIUM | 0 |
| A06: Vulnerable Components | ℹ️ Info | LOW | 0 |
| A07: Authentication Failures | 🔴 Fail | CRITICAL | 1 |
| A08: Data Integrity Failures | 🔴 Fail | CRITICAL | 1 |
| A09: Logging Failures | ⚠️ Review | MEDIUM | 0 |
| A10: SSRF | ✅ Pass | NONE | 0 |

---

## Critical Action Items (Immediate)

### Priority 1 - Must Fix Before Production

1. **Fix Kafka Deserialization Vulnerability** (A08)
   ```yaml
   # Change application.yml line 31 from:
   spring.json.trusted.packages: "*"
   # To:
   spring.json.trusted.packages: "com.mbd.shared.dto"
   ```

2. **Implement Authentication** (A07)
   - Add Spring Security OAuth2 Resource Server dependency
   - Configure JWT validation
   - Protect all endpoints with authentication

3. **Implement Authorization** (A01)
   - Add ownership checks before allowing portfolio/trade operations
   - Validate JWT `sub` claim matches account owner

### Priority 2 - Should Fix Soon

4. **Add Audit Logging** (A09)
   - Log all trade executions with user identity
   - Implement security event logging

5. **Secure Actuator Endpoints** (A05)
   - Add authentication to actuator endpoints
   - Reduce log level to INFO in production

6. **Add Input Validation** (A08)
   - Validate Kafka message content
   - Add schema validation

---

## Conclusion

The portfolio-service has **critical security vulnerabilities** that make it unsuitable for production deployment in its current state. The most critical issues are:

1. **Complete lack of authentication** - Anyone can view/trade on any account
2. **Unsafe Kafka deserialization** - Remote code execution risk
3. **Missing authorization** - No ownership verification

**Recommendation**: **DO NOT DEPLOY** until Priority 1 items are resolved.

---

## Compliance Impact

| Regulation | Impact | Notes |
|------------|--------|-------|
| **PCI DSS** | ❌ Non-compliant | No access control, no audit logging |
| **GDPR** | ❌ Non-compliant | No access control to personal financial data |
| **SOX** | ❌ Non-compliant | No audit trail for financial transactions |
| **MiFID II** | ❌ Non-compliant | Missing transaction logging |

---

*This report was generated through manual code review on 2026-09-04. It should be complemented with:*
- *Automated SAST scanning (CodeQL, SonarQube)*
- *Dynamic testing (DAST)*
- *Penetration testing*
- *Dependency vulnerability scanning*
