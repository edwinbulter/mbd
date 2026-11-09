# Security Tool Comparison: CodeQL vs Aikido vs Manual Review
## Portfolio Service - Complete Analysis

**Date**: 2026-09-04
**Service**: portfolio-service
**Compared Tools**: CodeQL, Aikido (free tier), Manual OWASP Review

---

## Table of Contents

1. [Methodology Note](#methodology-note)
2. [Executive Summary](#executive-summary)
   - [The Headline Result](#the-headline-result)
3. [Detailed Results Comparison](#detailed-results-comparison)
   - [OWASP Top 10 Category-by-Category](#owasp-top-10-category-by-category)
4. [Tool Capabilities Matrix](#tool-capabilities-matrix)
   - [What Each Tool Scans](#what-each-tool-scans)
   - [Vulnerability Detection Capabilities](#vulnerability-detection-capabilities)
5. [Critical Vulnerabilities Found](#critical-vulnerabilities-found)
   - [Vulnerability Summary Table](#vulnerability-summary-table)
6. [Why Claude Finds What SAST Tools Miss](#why-claude-finds-what-sast-tools-miss)
   - [How a SAST tool works](#how-a-sast-tool-works)
   - [How Claude works](#how-claude-works)
   - [Context the tools didn't have](#context-the-tools-didnt-have)
   - [Caveats: what this does *not* mean](#caveats-what-this-does-not-mean)
7. [Verification of Findings](#verification-of-findings)
   - [Why verification is needed](#why-verification-is-needed)
   - [Levels of evidence](#levels-of-evidence)
   - [Status per issue](#status-per-issue)
   - [Impact on the conclusions](#impact-on-the-conclusions)
   - [How to verify findings in future reviews](#how-to-verify-findings-in-future-reviews)
8. [Detailed Analysis: Why Tools Missed Critical Issues](#detailed-analysis-why-tools-missed-critical-issues)
   - [Issue #1: Unsafe Kafka Deserialization (CRITICAL)](#issue-1-unsafe-kafka-deserialization-critical)
   - [Issue #2: No Authentication (CRITICAL)](#issue-2-no-authentication-critical)
   - [Issue #3: No Authorization Checks (CRITICAL)](#issue-3-no-authorization-checks-critical)
   - [Issue #4: Race Condition in Distributed Transaction (HIGH)](#issue-4-race-condition-in-distributed-transaction-high)
   - [Issue #5: Exposed Actuator Endpoints (MEDIUM)](#issue-5-exposed-actuator-endpoints-medium)
9. [Tool Performance Scorecard](#tool-performance-scorecard)
   - [Technical Vulnerability Detection (Code-Level)](#technical-vulnerability-detection-code-level)
   - [Configuration & Architecture (Where Tools Failed)](#configuration--architecture-where-tools-failed)
10. [Cost-Benefit Analysis](#cost-benefit-analysis)
   - [CodeQL](#codeql)
   - [Aikido (Free Tier)](#aikido-free-tier)
   - [Aikido (AI Code Analysis - Paid)](#aikido-ai-code-analysis---paid)
   - [Manual OWASP Review](#manual-owasp-review)
11. [The False Sense of Security Problem](#the-false-sense-of-security-problem)
    - [How Developers Might Interpret Results](#how-developers-might-interpret-results)
    - [The Truth About "0 Vulnerabilities Found"](#the-truth-about-0-vulnerabilities-found)
12. [Real-World Analogy](#real-world-analogy)
    - [The Building Inspection Analogy (Revisited)](#the-building-inspection-analogy-revisited)
13. [Lessons Learned](#lessons-learned)
    - [1. Automated Tools Check "How", Not "If"](#1-automated-tools-check-how-not-if)
    - [2. "Configuration Analysis" Requires Context](#2-configuration-analysis-requires-context)
    - [3. SCA Is Aikido's Real Advantage](#3-sca-is-aikidos-real-advantage)
    - [4. "OWASP Top 10 Coverage" Misleading](#4-owasp-top-10-coverage-misleading)
    - [5. Manual Review Is Non-Negotiable for Production](#5-manual-review-is-non-negotiable-for-production)
14. [Recommendations](#recommendations)
    - [For Portfolio Service Specifically](#for-portfolio-service-specifically)
    - [For MBD Project Security Strategy](#for-mbd-project-security-strategy)
    - [What to Use Each Tool For](#what-to-use-each-tool-for)
15. [Compliance Impact](#compliance-impact)
    - [Regulatory Requirements](#regulatory-requirements)
16. [Conclusion](#conclusion)
    - [Summary of Findings](#summary-of-findings)
    - [The Central Finding](#the-central-finding)
    - [Final Verdict](#final-verdict)
    - [The Uncomfortable Truth](#the-uncomfortable-truth)
17. [References](#references)

---

## Methodology Note

**What "Manual Review" Means in This Document**:
The "Manual Review" referenced throughout this comparison was performed using **ClaudeCode prompts** to systematically validate the codebase against OWASP Top 10 security issues. This involved:
- Using ClaudeCode to analyze source code files
- Using ClaudeCode to review configuration files (YAML, properties)
- Using ClaudeCode to assess architectural security patterns
- Using ClaudeCode to validate business logic security
- Applying human security expertise through interactive prompts

**About Aikido's AI Code Analysis Feature**:
Aikido offers a premium "AI Code Analysis" feature that provides enhanced configuration scanning and more sophisticated vulnerability detection beyond the free tier. **This feature was NOT tested in this comparison** due to its very high cost. The Aikido results shown here are from the **free tier only**, which may explain why it missed configuration vulnerabilities that it advertises detecting.

It's possible that Aikido's paid AI tier would detect some of the configuration issues found by manual review (such as the unsafe Kafka deserialization pattern), but we cannot confirm this without testing.

---

## Executive Summary

Three different security assessment approaches were applied to the same codebase with dramatically different results:

| Assessment Method | Issues Found | Critical Issues | Overall Verdict |
|-------------------|--------------|-----------------|-----------------|
| **CodeQL** (Free) | 0 | 0 | ✅ PASS - No vulnerabilities |
| **Aikido** (Free tier) | 0 | 0 | ✅ PASS - No vulnerabilities |
| **Manual Review** | 8 | 3 reported, 2 after verification | 🔴 FAIL - DO NOT DEPLOY |

All 8 manual-review findings were confirmed in the code afterwards, but none has been demonstrated against a running system yet. See [Verification of Findings](#verification-of-findings).

### The Headline Result

**Both automated tools reported the codebase as secure** (0 vulnerabilities found), yet **manual review found 8 issues, including deployment-blocking ones**. 3 were reported as CRITICAL. After verification, 2 remain CRITICAL, among them a missing ownership check that lets any logged-in customer trade on another customer's account.

**This demonstrates**: Passing automated security scans ≠ Being secure

---

## Detailed Results Comparison

### OWASP Top 10 Category-by-Category

**Uses the OWASP Top 10:2025 edition** (released January 2026 — the first update since 2021). This table was recategorized from the original 2021-edition version; see the note below it for what changed and how counts were derived.

| OWASP Category | CodeQL | Aikido | Manual Review | Winner |
|----------------|--------|--------|---------------|--------|
| **A01: Broken Access Control** | ✅ Pass | ✅ Pass | 🔴 1 CRITICAL | Manual ⚠️ |
| **A02: Security Misconfiguration** | ✅ Pass | ✅ Pass | ⚠️ 2 (1 MEDIUM, 1 LOW) | Manual ⚠️ |
| **A03: Software Supply Chain Failures** | ℹ️ Not covered | ✅ Pass (SCA) | ℹ️ Not assessed | Aikido ✅ |
| **A04: Cryptographic Failures** | ✅ Pass | ✅ Pass | ✅ Pass | All 🤝 |
| **A05: Injection** | ✅ Pass | ✅ Pass | ✅ Pass | All 🤝 |
| **A06: Insecure Design** | ✅ Pass | ✅ Pass | 🔴 1 HIGH | Manual ⚠️ |
| **A07: Authentication Failures** | ✅ Pass | ✅ Pass | 🔴 1 CRITICAL | Manual ⚠️ |
| **A08: Software or Data Integrity Failures** | ✅ Pass | ✅ Pass | 🔴 2 (1 CRITICAL, 1 MEDIUM) | Manual ⚠️ |
| **A09: Security Logging and Alerting Failures** | ℹ️ Not covered | ✅ Pass | ⚠️ 1 MEDIUM | Manual ⚠️ |
| **A10: Mishandling of Exceptional Conditions** | ℹ️ Not covered | ℹ️ Not covered | ℹ️ Not assessed | — |

**Legend**: ✅ No issues | ⚠️ Issues found | 🔴 Critical issues | N/A Not scanned | ℹ️ Info

**What changed from the 2021 edition, and how these counts were derived**: the 2025 edition dropped "Vulnerable and Outdated Components" and "SSRF" as standalone categories and added "Software Supply Chain Failures" (A03) and "Mishandling of Exceptional Conditions" (A10); Security Misconfiguration moved from #5 to #2, and Insecure Design moved from #4 to #6. Counts above reflect each of the 8 issues' **primary** category only (several issues also touch a secondary category — see the per-issue `OWASP Category` column in the Vulnerability Summary Table below and each issue's own subsection). A03 and A10 are new in 2025, so "not covered"/"not assessed" there reflects that none of the three methods specifically targeted those categories — not a confirmed pass.

---

## Tool Capabilities Matrix

### What Each Tool Scans

| Scan Type | CodeQL | Aikido (Free) | Aikido (AI Paid) | Manual Review |
|-----------|--------|---------------|------------------|---------------|
| **Source Code (Java/Kotlin)** | ✅ Yes | ✅ Yes | ✅ Yes | ✅ Yes |
| **Configuration Files (YAML)** | ❌ No | ⚠️ Limited | ✅ Yes | ✅ Yes |
| **Dependencies (SCA)** | ❌ No | ✅ Yes | ✅ Yes | ⚠️ Manual |
| **Secrets Scanning** | ⚠️ Limited | ✅ Yes | ✅ Yes | ⚠️ Manual |
| **Container Images** | ❌ No | ✅ Yes | ✅ Yes | ⚠️ Manual |
| **Business Logic** | ❌ No | ❌ No | ⚠️ Limited | ✅ Yes |
| **Architecture Gaps** | ❌ No | ❌ No | ❌ No | ✅ Yes |

### Vulnerability Detection Capabilities

| Vulnerability Type | CodeQL | Aikido (Free) | Manual Review | Example from Portfolio Service |
|-------------------|--------|---------------|---------------|-------------------------------|
| **SQL Injection** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | None found (using Spring Data JPA) |
| **XSS** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | None found (backend service) |
| **Command Injection** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | None found |
| **Path Traversal** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | None found |
| **Hardcoded Secrets** | ⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | None found |
| **Weak Crypto** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | None found |
| **Dependency CVEs** | ⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐ | None found by Aikido |
| **YAML Config Issues** | ⭐ | ⭐⭐ | ⭐⭐⭐⭐⭐ | **MISSED**: Unsafe Kafka config |
| **Missing Authentication** | ⭐ | ⭐ | ⭐⭐⭐⭐⭐ | **MISSED**: No Spring Security |
| **Missing Authorization** | ⭐ | ⭐ | ⭐⭐⭐⭐⭐ | **MISSED**: No ownership checks |
| **Race Conditions** | ⭐⭐ | ⭐ | ⭐⭐⭐⭐⭐ | **MISSED**: TOCTOU in trades |
| **Missing Audit Logs** | ⭐ | ⭐ | ⭐⭐⭐⭐⭐ | **MISSED**: No trade logging |

**Legend**: ⭐⭐⭐⭐⭐ Excellent | ⭐⭐⭐⭐ Good | ⭐⭐⭐ Moderate | ⭐⭐ Limited | ⭐ Poor/Cannot detect

---

## Critical Vulnerabilities Found

### Vulnerability Summary Table

| # | Vulnerability | Severity | CWE | OWASP Category (2025) | CodeQL | Aikido | Manual | Status |
|---|---------------|----------|-----|-----------------|--------|--------|--------|--------|
| 1 | Unsafe Kafka deserialization (`spring.json.trusted.packages: "*"`) | CRITICAL | CWE-502 | **A08:2025** – Software or Data Integrity Failures (also A02 – Security Misconfiguration) | ❌ | ❌ | ✅ | **MISSED by both tools** |
| 2 | No authentication implemented | CRITICAL | CWE-306 | **A07:2025** – Authentication Failures (also A01 – Broken Access Control) | ❌ | ❌ | ✅ | **MISSED by both tools** |
| 3 | No authorization checks | CRITICAL | CWE-862 | **A01:2025** – Broken Access Control | ❌ | ❌ | ✅ | **MISSED by both tools** |
| 4 | Race condition in distributed transaction | HIGH | CWE-367 | **A06:2025** – Insecure Design | ❌ | ❌ | ✅ | **MISSED by both tools** |
| 5 | Exposed actuator endpoints | MEDIUM | - | **A02:2025** – Security Misconfiguration | ❌ | ❌ | ✅ | **MISSED by both tools** |
| 6 | No audit logging for trades | MEDIUM | - | **A09:2025** – Security Logging and Alerting Failures | ❌ | ❌ | ✅ | **MISSED by both tools** |
| 7 | No Kafka message validation | MEDIUM | - | **A08:2025** – Software or Data Integrity Failures (also A06 – Insecure Design) | ❌ | ❌ | ✅ | **MISSED by both tools** |
| 8 | Debug logging enabled | LOW | - | **A02:2025** – Security Misconfiguration (also A09 – Logging and Alerting Failures, if sensitive data is logged) | ❌ | ❌ | ✅ | **MISSED by both tools** |

**Result**: all 8 issues identified in this study were found ONLY by manual review. The Severity column shows the severity as reported. Verified severities differ for #2 and #5. See [Status per issue](#status-per-issue).

**Note on OWASP edition**: these mappings use the **OWASP Top 10:2025** (released January 2026), not the 2021 edition this document originally referenced when these per-issue mappings were first added. Two of the eight primary mappings shifted numbers as a result: Issue #4 (Insecure Design) moved from A04:2021 to **A06:2025**, and Issue #5 (Security Misconfiguration) moved from A05:2021 to **A02:2025**, since Security Misconfiguration jumped from #5 to #2 in the new edition. Issues #1, #2, #3, #6 land on the same number in both editions by coincidence, not because those categories are unchanged. The category-by-category table above (§"OWASP Top 10 Category-by-Category") is kept consistent with these primary mappings — treat this table as the authoritative per-issue classification, and that one as the aggregated, coarser view.

---

## Why Claude Finds What SAST Tools Miss

The per-issue sections below explain each miss individually. They share one root cause: **SAST tools and Claude answer different questions.**

### How a SAST tool works

CodeQL and Aikido's free-tier SAST are **rule-based engines**. CodeQL compiles the code into a database and runs a fixed library of queries against it. Most queries are **taint-flow rules**: "does untrusted data flow from a *source* (e.g. an HTTP parameter) into a dangerous *sink* (e.g. a SQL query, a shell command, an HTML response) without passing through a *sanitizer*?"

So a SAST tool can only report a vulnerability when:

1. **Something is present in the code** that matches a rule (a sink, a weak API call, a hardcoded string), and
2. **Someone has already written a rule** for that pattern, for that language and framework, and
3. **The relevant code is in the files the tool parses** (for CodeQL, `.java`/`.kt` source, not `application.yml`).

That explains the results. portfolio-service uses Spring Data JPA and Feign, so there are no raw-SQL sinks, no shell calls and no HTML rendering. The rule library has nothing to match, and "0 vulnerabilities" is a correct answer to the question the tool asks.

### How Claude works

Claude is a large language model. It doesn't match rules. It **reads the code, the configuration and the documentation as a whole and reasons about what the system is meant to do**, then compares that against the OWASP Top 10 as a checklist. That changes four things:

| Capability | SAST (rule-based) | Claude (reasoning-based) | Issues it explains |
|------------|-------------------|--------------------------|--------------------|
| **Detecting absence** | Can only flag code that exists. A missing `SecurityFilterChain` produces no node to match. | Knows what a secure Spring Boot service *should* contain and notices what isn't there ("there is no Spring Security dependency or config"). | #2, #3, #6 |
| **Understanding intent and domain** | Has no concept of "account", "owner" or "trade". `getPortfolio(accountId)` is just a method with a `Long` parameter. | Infers from names and flow that `accountId` belongs to a customer and that nothing checks the caller owns it (IDOR / BOLA). | #3, #4 |
| **Reading configuration semantically** | Doesn't parse `application.yml` (CodeQL), or only checks generic YAML patterns (Aikido free). | Knows that `spring.json.trusted.packages: "*"` turns off Spring Kafka's type allow-list, and that `show-details: always` leaks internals. | #1, #5, #8 |
| **Reasoning across boundaries** | Analyses data flow inside one program. A Feign call to another service is a black box. | Follows the trade flow across `AccountClient` → account-service and sees the check-then-act (TOCTOU) gap between two separate HTTP calls. | #4, #7 |

Put briefly: **SAST asks "is there a known-bad pattern in this code?"; Claude asks "given what this service does, which OWASP controls should be here, and are they here and correct?"** Most of the 8 findings are *missing* controls or *design* flaws, which only the second question can find.

### Context the tools didn't have

Claude also used information outside the source code:

- **Project documentation**: `CLAUDE.md` states: "Only `admin-service` implements Spring Security OAuth2 resource server. Other services rely on Istio mesh authentication." Claude loads this file automatically, so it gets a direct pointer to Issue #2 that a SAST tool never reads.
- **Framework knowledge**: Spring Kafka's deserialization model, Spring Boot Actuator defaults and Spring Security conventions are part of the model's training. No rule for them has to exist in a query pack.
- **An explicit checklist**: the prompt asked Claude to go through the OWASP Top 10 category by category. That makes it look for gaps in every category, rather than waiting for a pattern to fire.

### Caveats: what this does *not* mean

The result is real, but it doesn't make Claude a drop-in replacement for SAST:

- **No completeness guarantee.** A CodeQL query checks every matching code path deterministically. Claude reads selectively and can overlook a path. "Claude found nothing" is weaker evidence than "CodeQL found nothing" *for the classes CodeQL covers*.
- **Non-deterministic.** The same prompt can give somewhat different findings on another run. SAST output is reproducible and fits a CI gate.
- **False positives and hallucinations.** An LLM can report an issue that isn't there, or misjudge its severity. In this study, the severities also moved between documents. Claude's original report (`claude-OWASP-validation-result.md`) rated authorization (#3) HIGH and the race condition (#4) MEDIUM. This comparison raised them to CRITICAL and HIGH. Every finding needs human verification. The next section does that.
- **Weak on what SAST is strong at.** For deep taint tracking through large codebases, and for known CVEs in dependencies (SCA), rule-based tools and vulnerability databases are more reliable.
- **Human in the loop.** "Manual Review" in this document means Claude *driven by interactive prompts and checked by a person*. It is not an unattended scan.

**Conclusion**: the approaches complement each other. SAST reliably catches known code patterns at scale. Claude catches missing controls, design flaws and misconfigurations that no rule describes. This is why the recommended layered approach below keeps both.

---

## Verification of Findings

The previous section notes that an LLM can report issues that aren't there, or misjudge how severe they are. This section applies that caveat to this study's own results.

### Why verification is needed

A finding from Claude is a **hypothesis**, not proof. The follow-up `Issue-*-Fix-Analysis.md` documents re-checked every finding against the code, configuration and Istio policies. However, **none of the 8 findings has been exploited against a running system**, and no test reproduces any of them. So the claim that manual review "found all issues" has not been independently tested either.

Two kinds of error are worth separating:

- **False positive**: the vulnerability doesn't exist.
- **Overstated severity**: the issue is real, but less serious than reported, for example because another layer such as Istio already blocks part of the attack.

After verification, **no finding turned out to be a false positive**. Several were rated too severe or too broadly, and one (#8) is latent rather than active.

### Levels of evidence

| Level | Meaning |
|-------|---------|
| **L1 – Confirmed in code** | The setting, code path or missing control is verifiably present (or absent) in source, config and policies. |
| **L2 – Demonstrated at runtime** | The attack was carried out against the running cluster and worked. |
| **L3 – Captured in a test** | An automated test fails before the fix and passes after it. |

### Status per issue

"Reported" is the severity used in this comparison. Where Claude's original report (`claude-OWASP-validation-result.md`) used a different severity, it is given in brackets.

| # | Finding | Reported | Evidence | What verification found | Verified severity | Verdict |
|---|---------|----------|----------|-------------------------|-------------------|---------|
| 1 | Unsafe Kafka deserialization | CRITICAL | L1 | The wildcard disabled Spring Kafka's type allow-list. Kafka in this cluster has no authentication or ACLs (Issue #7 analysis), so any workload that can reach Kafka can publish. Remote code execution additionally needs a usable gadget class on the classpath. That was **not demonstrated**. Fixed in `8ca29b3` (no test added). | CRITICAL (exploitability not demonstrated) | Confirmed, fixed |
| 2 | No authentication | CRITICAL | L1 | The application had no authentication. External traffic through the gateway already needed a valid JWT. However, `api-access-policy` lets any pod in the `mbd` namespace call the API without a JWT, and `kubectl port-forward` bypasses Istio entirely. "Complete authentication bypass" overstates the blast radius. Fixed in `225b34d` (no test added). | HIGH | Confirmed, severity overstated, fixed |
| 3 | No authorization | CRITICAL (HIGH) | L1 | `api-access-policy` accepts *any* valid JWT, so any logged-in customer can request or trade on another customer's account through the public gateway. account-service's own ownership check is bypassed, because portfolio-service's Feign client doesn't forward the caller's identity. | CRITICAL | Confirmed, strongest finding |
| 4 | Race condition (TOCTOU) | HIGH (MEDIUM) | L1 | The balance check and the debit are two separate HTTP calls. `Holding` has no `@Version` and there is no lock, so concurrent SELLs can also drive `quantity` negative. Not reproduced under load. | HIGH | Confirmed |
| 5 | Exposed actuator endpoints | MEDIUM | L1 | There is no gateway route to `/actuator`, so it's not reachable from the internet. Inside the mesh it is probably denied by `api-access-policy` (not verified live). It is reachable through `kubectl port-forward`, which bypasses Istio. This follows from how port-forward works and was not executed. The audience is "anyone with kubectl access". | LOW | Confirmed, severity overstated |
| 6 | No audit logging | MEDIUM | L1 | No trade audit trail exists. This is a missing control (compliance and forensics), not something an attacker can exploit directly. | MEDIUM | Confirmed (missing control, not a vulnerability) |
| 7 | No Kafka message validation | MEDIUM | L1 | Consumers apply `newPrice` and config values without bounds checks. Because Kafka is unauthenticated, any publisher can corrupt holding values. Not demonstrated. | MEDIUM | Confirmed |
| 8 | Debug logging enabled | LOW | L1 | `DEBUG` is configured, but there is no `logger.debug(...)` call anywhere in the backend. Today nothing is logged at that level. | LOW (preventive) | Latent |

**Summary**: all 8 at **L1**, none at L2 or L3. After verification there are **2 CRITICAL** issues (#1, #3) instead of 3, **2 HIGH** (#2, #4), **2 MEDIUM** (#6, #7) and **2 LOW** (#5, #8). Note that this comparison raised two severities above Claude's own report (#3 from HIGH, #4 from MEDIUM). Verification supports both increases.

### Impact on the conclusions

- **The core finding stands.** All 8 issues exist at L1. CodeQL and Aikido (free tier) reported none of them, including the milder ones.
- **Severities shift.** "3 CRITICAL" becomes **2 CRITICAL**, and one of those two (#1) has an exploit path that has not been demonstrated. The deployment verdict doesn't change: #3 alone (any customer can trade on any account) blocks production.
- **Completeness is unknown.** There is no independent baseline, so this study can't say how many issues *all three* methods missed. The "false negatives" figure for manual review is therefore reported as unknown.

### How to verify findings in future reviews

1. **Capture each finding in a test.** Examples: a `MockMvc` test for "no token → 401" (#2) and "other user's account → 403" (#3), and a test with two concurrent trades for #4. This brings a finding to L3 and doubles as a regression test.
2. **Demonstrate at runtime where possible.** For example, `curl` without a token via port-forward (#2, #5), a valid JWT for user A against user B's account (#3), and a negative `newPrice` published to `fund-price-updates` (#7).
3. **Include the surrounding layers when rating severity.** Istio policies, gateway routes and Kafka ACLs determine the real blast radius. That is where reported and verified severity differed most (#2, #5).
4. **Run an adversarial second review.** Ask a separate session or model to argue that each finding is *not* a vulnerability. This doesn't replace L2/L3 evidence, but it filters out the weakest claims early.

---

## Detailed Analysis: Why Tools Missed Critical Issues

### Issue #1: Unsafe Kafka Deserialization (CRITICAL)

**Location**: `application.yml:31`

**OWASP Category**: A08:2025 – Software or Data Integrity Failures (insecure deserialization is one of A08's flagship examples); also touches A02 – Security Misconfiguration, since the root cause is a wildcard trust setting rather than a code defect.

**Vulnerability**:
```yaml
spring:
  kafka:
    consumer:
      properties:
        spring.json.trusted.packages: "*"  # WILDCARD - RCE RISK!
```

#### Why CodeQL Missed It:
- ❌ **Doesn't scan YAML files** - CodeQL analyzes source code only (`.java`, `.kt` files)
- ❌ **Configuration vulnerabilities not in scope**
- ❌ **No rules for Spring Kafka config patterns**

#### Why Aikido (Free Tier) Missed It:
- ❌ **Configuration scanning requires paid "AI Code Analysis" feature**
- ❌ **Free tier focuses on code-level patterns**
- ⚠️ **Might detect with paid tier** (not tested - too expensive)

#### Why Manual Review Found It:
- ✅ **Reviews all configuration files as part of standard security assessment**
- ✅ **Understands Spring Boot/Kafka security best practices**
- ✅ **Recognizes wildcard deserialization as critical RCE vector**

**Impact**: CVSS 9.8 (unverified estimate) - Remote Code Execution via malicious Kafka messages. Remote code execution requires a usable gadget class and was not demonstrated; see [Verification of Findings](#verification-of-findings).

---

### Issue #2: No Authentication (CRITICAL)

**Location**: Entire service - no Spring Security configuration

**OWASP Category**: A07:2025 – Authentication Failures; a system with no authentication at all is also a limiting case of A01 – Broken Access Control, since access control is meaningless without a verified identity to control access for.

**Vulnerability**:
```kotlin
@RestController
@RequestMapping("/api/portfolio")
class PortfolioController {
    // NO AUTHENTICATION - Anyone can access!
    @GetMapping("/{accountId}")
    fun getPortfolio(@PathVariable accountId: Long): ResponseEntity<PortfolioDto>
}
```

#### Why CodeQL Missed It:
- ❌ **Checks for BROKEN authentication patterns, not MISSING authentication**
- ❌ **Cannot detect absence of security features**
- ❌ **No Spring Security configuration to analyze**
- 💡 **Tool logic**: "If no bad auth code exists, report pass" (but no auth exists at all!)

#### Why Aikido Missed It:
- ❌ **Same limitation - detects insecure implementations, not missing features**
- ❌ **No pattern to match when feature is completely absent**
- ❌ **AI analysis might not help - this is an architectural gap**

#### Why Manual Review Found It:
- ✅ **Checks that security controls EXIST, not just that they're correct**
- ✅ **Validates defense-in-depth (not just edge authentication)**
- ✅ **Verifies Spring Security is configured for all services**

**Impact**: CVSS 10.0 (unverified estimate) - Complete authentication bypass *at the application level*. Verification found the blast radius is narrower: gateway traffic already required a JWT; the gap is same-namespace pods and `kubectl port-forward`. Verified severity: HIGH.

---

### Issue #3: No Authorization Checks (CRITICAL)

**Location**: `PortfolioController.kt`, `PortfolioService.kt`

**OWASP Category**: A01:2025 – Broken Access Control. This is the canonical A01 case: an authenticated (or even unauthenticated) caller can act on resources — another customer's account and holdings — without any check that they're entitled to.

**Vulnerability**:
```kotlin
@PostMapping("/trade")
fun executeTrade(@RequestBody trade: TradeDto): ResponseEntity<HoldingDto> {
    // NO CHECK: Does user own this account?
    val holding = portfolioService.executeTrade(trade)
    return ResponseEntity.ok(holding)
}
```

#### Why CodeQL Missed It:
- ❌ **Cannot understand business logic** (account ownership rules)
- ❌ **No pattern for "missing authorization check"**
- ❌ **Doesn't know what data belongs to which user**

#### Why Aikido Missed It:
- ❌ **Same limitation - business logic is outside SAST scope**
- ❌ **Cannot infer data ownership relationships**
- ❌ **Even AI analysis likely can't detect domain-specific access rules**

#### Why Manual Review Found It:
- ✅ **Understands application domain** (financial services, account ownership)
- ✅ **Verifies authorization logic matches business requirements**
- ✅ **Checks JWT claims are validated against resource ownership**

**Impact**: CVSS 9.1 (unverified estimate) - Horizontal privilege escalation, financial fraud

---

### Issue #4: Race Condition in Distributed Transaction (HIGH)

**Location**: `PortfolioService.kt:48-57`

**OWASP Category**: A06:2025 – Insecure Design. The check-then-act balance validation is a design-level gap, not a coding typo — the trade flow was never designed to account for concurrent execution, which is exactly the class of flaw A06 targets (as distinct from A05/A02, which are about implementation and configuration mistakes).

**Vulnerability**:
```kotlin
val account = accountClient.getAccount(trade.accountId)  // HTTP call 1
if (account.balance < totalCost) {
    throw IllegalStateException("Insufficient balance")
}
accountClient.updateBalance(...)  // HTTP call 2 - TOCTOU gap!
```

#### Why CodeQL Missed It:
- ⚠️ **Race condition detection exists BUT only for single JVM multi-threading**
- ❌ **Doesn't analyze concurrency across HTTP boundaries**
- ❌ **Cannot trace state changes through Feign clients**

#### Why Aikido Missed It:
- ❌ **Same limitation - distributed system race conditions not detected**
- ❌ **Treats each HTTP client call as independent**
- ❌ **No understanding of transactional boundaries across services**

#### Why Manual Review Found It:
- ✅ **Analyzes flow across service boundaries**
- ✅ **Recognizes TOCTOU patterns in distributed systems**
- ✅ **Understands need for distributed locking or optimistic concurrency**

**Impact**: Account overdraft, financial integrity violation

---

### Issue #5: Exposed Actuator Endpoints (MEDIUM)

**Location**: `application.yml:36-40`

**OWASP Category**: A02:2025 – Security Misconfiguration. This is the textbook A02 example: unnecessary management endpoints left enabled with default, unauthenticated access and verbose (`show-details: always`) output.

**Vulnerability**:
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus  # Exposed!
```

#### Why CodeQL Missed It:
- ❌ **Doesn't scan YAML files**

#### Why Aikido (Free) Missed It:
- ❌ **Configuration analysis requires paid tier**
- ⚠️ **This was supposed to be Aikido's advantage over CodeQL**

#### Why Manual Review Found It:
- ✅ **Reviews all Spring Boot configuration**
- ✅ **Knows actuator security best practices**

**Impact**: Information disclosure, potential DoS

---

## Tool Performance Scorecard

### Technical Vulnerability Detection (Code-Level)

| Vulnerability Type | CodeQL | Aikido (Free) | Manual | Winner |
|-------------------|--------|---------------|--------|--------|
| SQL Injection | ✅ Would detect | ✅ Would detect | ✅ Verified safe | Tie (all correct) |
| XSS | ✅ Would detect | ✅ Would detect | ✅ Verified safe | Tie (all correct) |
| Command Injection | ✅ Would detect | ✅ Would detect | ✅ Verified safe | Tie (all correct) |
| Hardcoded Secrets | ✅ Would detect | ✅ Would detect | ✅ Verified safe | Tie (all correct) |
| Weak Crypto | ✅ Would detect | ✅ Would detect | ✅ Verified safe | Tie (all correct) |

**Score**: CodeQL ✅ | Aikido ✅ | Manual ✅

**Conclusion**: All three methods correctly validated absence of common code-level vulnerabilities.

---

### Configuration & Architecture (Where Tools Failed)

| Issue Type | CodeQL | Aikido (Free) | Manual | Winner |
|-----------|--------|---------------|--------|--------|
| YAML Config Vulnerabilities | ❌ Missed all | ❌ Missed all | ✅ Found all | Manual only |
| Missing Authentication | ❌ Missed | ❌ Missed | ✅ Found | Manual only |
| Missing Authorization | ❌ Missed | ❌ Missed | ✅ Found | Manual only |
| Business Logic Flaws | ❌ Missed | ❌ Missed | ✅ Found | Manual only |
| Race Conditions (Distributed) | ❌ Missed | ❌ Missed | ✅ Found | Manual only |
| Missing Audit Logging | ❌ Missed | ❌ Missed | ✅ Found | Manual only |

**Score**: CodeQL ❌ | Aikido ❌ | Manual ✅

**Conclusion**: Only manual review detected architectural and configuration issues.

---

## Cost-Benefit Analysis

### CodeQL

**Cost**: ✅ Free (included with GitHub)

**Benefits**:
- ✅ Excellent code-level vulnerability detection
- ✅ Fast automated scanning
- ✅ Good for CI/CD integration
- ✅ Large community and query library

**Limitations**:
- ❌ No configuration file scanning
- ❌ No dependency scanning
- ❌ Cannot detect missing security controls

**Recommendation**: ⭐⭐⭐⭐ - Use as baseline automated check

---

### Aikido (Free Tier)

**Cost**: ✅ Free tier available

**Benefits**:
- ✅ Code-level vulnerability detection (similar to CodeQL)
- ✅ Dependency/SCA scanning (advantage over CodeQL)
- ✅ Secrets detection
- ✅ Container scanning

**Limitations**:
- ❌ Configuration scanning requires expensive "AI Code Analysis" tier
- ❌ Cannot detect missing security controls
- ❌ Free tier didn't outperform CodeQL on this codebase

**Recommendation**: ⭐⭐⭐ - Good for dependency scanning, but free tier didn't find more than CodeQL

---

### Aikido (AI Code Analysis - Paid)

**Cost**: 💰 Very expensive (not tested)

**Expected Benefits**:
- ✅ Should scan YAML configuration files
- ✅ Might detect framework-specific misconfigurations
- ⚠️ Still likely can't detect missing authentication/authorization

**Limitations**:
- ❌ Cannot detect architectural gaps
- ❌ Cannot detect business logic flaws
- 💰 High cost for incremental benefit

**Recommendation**: ⭐⭐ - Not tested; likely not worth cost vs manual review

---

### Manual OWASP Review

**Cost**: ⏰ Time investment (expert hours)

**Benefits**:
- ✅ Found all 8 issues identified in this study (completeness untested, no independent baseline)
- ✅ Detects architectural gaps
- ✅ Detects business logic flaws
- ✅ Detects configuration issues
- ✅ Provides remediation guidance
- ✅ Compliance validation

**Limitations**:
- ⏰ Time-consuming
- 💼 Requires security expertise
- 🔄 Not easily automated

**Recommendation**: ⭐⭐⭐⭐⭐ - Essential for production deployment decision

---

## The False Sense of Security Problem

### How Developers Might Interpret Results

#### Scenario: Relying Only on Automated Tools

```
Developer sees:
  ✅ CodeQL: 0 vulnerabilities found
  ✅ Aikido: 0 vulnerabilities found

Developer concludes:
  ✅ "Our code passed security scans!"
  ✅ "Safe to deploy to production!"
  ✅ "We're OWASP Top 10 compliant!"

Reality:
  🔴 2 CRITICAL vulnerabilities exist (3 as reported)
  🔴 Any logged-in customer can trade on another customer's account
  🔴 Financial data exposed
  🔴 PCI DSS / GDPR non-compliant
```

**This is dangerous.**

---

### The Truth About "0 Vulnerabilities Found"

Both CodeQL and Aikido reported **"0 vulnerabilities"** - but what does this actually mean?

| Statement | What It Means | What It DOESN'T Mean |
|-----------|---------------|---------------------|
| "No SQL injection found" | ✅ No string concatenation in queries | ❌ Doesn't mean data access is secure |
| "No hardcoded secrets found" | ✅ No passwords in source code | ❌ Doesn't mean auth is implemented |
| "No XSS vulnerabilities" | ✅ No unsafe HTML rendering | ❌ Doesn't mean service is secure |
| "Passed OWASP Top 10 scan" | ✅ No code-level OWASP patterns | ❌ Doesn't mean OWASP compliant |

**The scans check for specific CODE PATTERNS, not overall security posture.**

---

## Real-World Analogy

### The Building Inspection Analogy (Revisited)

Imagine three building inspections:

#### CodeQL = Structural Engineer
- ✅ Checks electrical wiring meets code
- ✅ Verifies plumbing has no leaks
- ✅ Validates construction materials are rated
- ❌ Doesn't check for locks on doors (not in scope)

**Report**: "Building passes structural inspection ✅"

---

#### Aikido (Free) = Safety Inspector
- ✅ Everything the structural engineer checks
- ✅ PLUS: Checks fire extinguishers are up to date
- ✅ PLUS: Verifies smoke alarms installed
- ❌ Doesn't check for locks on doors (not in scope)

**Report**: "Building passes safety inspection ✅"

---

#### Aikido (Paid) = Premium Safety Inspector
- ✅ Everything free tier checks
- ✅ PLUS: Might check some security basics
- ⚠️ **Still doesn't check if locks exist** (architectural gap)
- 💰 Costs 10x more than free tier

**Report**: "Building passes premium inspection ✅"

---

#### Manual Security Review = Security Consultant
- ✅ Checks everything above
- 🔴 **Notices: Building has NO LOCKS on any doors**
- 🔴 **Notices: Vault has NO COMBINATION**
- 🔴 **Notices: Security cameras not connected**
- 🔴 **Notices: Anyone can walk in and take anything**

**Report**: "Building FAILS security review - DO NOT OCCUPY 🔴"

---

**All three inspectors checked "security":**
- Structural engineer: "All installed locks work properly" ✅
- Safety inspector: "All installed locks meet standards" ✅
- Security consultant: "There ARE NO LOCKS AT ALL" 🔴

**None of the inspectors is wrong - they're checking different things.**

---

## Lessons Learned

### 1. Automated Tools Check "How", Not "If"

**CodeQL & Aikido**:
- Check **HOW** you implemented security features
- Cannot check **IF** you implemented security features

**Example**:
- ✅ Can detect: Weak password hashing (MD5 instead of bcrypt)
- ❌ Cannot detect: No password authentication at all

---

### 2. "Configuration Analysis" Requires Context

**Aikido advertises**:
- "Configuration file scanning"
- "YAML security analysis"

**Reality**:
- Free tier: Didn't scan YAML effectively
- Paid tier: Unknown (not tested)
- Issue: Generic YAML scanning ≠ Spring Boot security expertise

**Lesson**: Tool capabilities vary by framework and feature tier.

---

### 3. SCA Is Aikido's Real Advantage

**Where Aikido beat CodeQL**:
- ✅ Dependency vulnerability scanning (CVEs)
- ✅ Outdated library detection
- ✅ License compliance

**Where both failed equally**:
- ❌ Configuration vulnerabilities
- ❌ Missing security controls
- ❌ Business logic flaws

**Lesson**: Use Aikido for dependency scanning, not as sole security assessment.

---

### 4. "OWASP Top 10 Coverage" Misleading

**What tools claim**:
> "Scans for OWASP Top 10 vulnerabilities ✅"

**What this actually means**:
> "Scans for CODE PATTERNS related to OWASP Top 10"

**What it doesn't mean**:
> "Validates compliance with OWASP Top 10 requirements"

**Example - A01 Broken Access Control**:
- Tools check: Authorization bypass patterns in code
- Tools don't check: If authorization exists at all

---

### 5. Manual Review Is Non-Negotiable for Production

**For production deployment, you MUST have**:
1. ✅ Automated SAST (CodeQL)
2. ✅ Automated SCA (Aikido or similar)
3. ✅ **Manual security review** ← The only layer that found the critical issues
4. ✅ Penetration testing
5. ✅ Compliance audit

**Skipping manual review = Deploying with critical vulnerabilities**

---

## Recommendations

### For Portfolio Service Specifically

**Do NOT deploy until these are fixed**:

1. 🔴 **CRITICAL**: Change `spring.json.trusted.packages: "*"` to `"com.mbd.shared.dto"`
2. 🔴 **CRITICAL**: Implement Spring Security OAuth2 Resource Server
3. 🔴 **CRITICAL**: Add authorization checks (verify user owns account)
4. 🟠 **HIGH**: Implement distributed locking for trade operations
5. 🟠 **HIGH**: Secure actuator endpoints with authentication
6. 🟡 **MEDIUM**: Add audit logging for all trades
7. 🟡 **MEDIUM**: Add Kafka message validation

**Deployment Status**: 🔴 **BLOCKED** - Critical vulnerabilities must be resolved

---

### For MBD Project Security Strategy

#### Recommended Layered Approach

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 1: CodeQL (Automated SAST)                            │
│ Purpose: Catch common code vulnerabilities                  │
│ Cost: Free                                                   │
│ Run: On every PR                                             │
│ Coverage: SQL injection, XSS, command injection, etc.       │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ Layer 2: Aikido Free (SCA + Secrets)                        │
│ Purpose: Dependency vulnerability scanning                  │
│ Cost: Free                                                   │
│ Run: Weekly or on dependency updates                        │
│ Coverage: CVEs in libraries, exposed secrets                │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ Layer 3: Manual OWASP Security Review ⭐ CRITICAL            │
│ Purpose: Architecture, business logic, config validation    │
│ Cost: Expert time investment                                │
│ Run: Before EVERY production deployment                     │
│ Coverage: Everything automated tools miss                   │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│ Layer 4: Penetration Testing                                │
│ Purpose: Runtime vulnerability validation                   │
│ Cost: Varies                                                 │
│ Run: Before major releases                                  │
│ Coverage: Actual exploitability of issues                   │
└─────────────────────────────────────────────────────────────┘
```

**Critical**: Never skip Layer 3 (Manual Review). It was the only layer that found the deployment-blocking issues. Its findings still need verification, which is what Layer 4 and tests provide.

---

### What to Use Each Tool For

#### Use CodeQL For:
✅ Continuous code-level vulnerability scanning
✅ CI/CD integration (fast feedback)
✅ Finding common OWASP code patterns
✅ Preventing regressions

❌ Don't rely on for: Configuration security, architectural validation

---

#### Use Aikido (Free) For:
✅ Dependency vulnerability scanning (CVEs)
✅ Secrets detection
✅ License compliance
✅ Container image scanning

❌ Don't rely on for: Configuration security (free tier), missing controls

---

#### Use Manual Review For:
✅ **Pre-production security sign-off** ⭐
✅ Architecture and design validation
✅ Business logic security
✅ Configuration security
✅ Missing security controls
✅ Compliance validation
✅ Threat modeling

❌ Don't use for: Finding common code patterns (use automation)

---

## Compliance Impact

### Regulatory Requirements

| Regulation | Automated Tools Status | Manual Review Status | Deployment Impact |
|------------|------------------------|----------------------|-------------------|
| **PCI DSS** | ✅ "Passed scans" | ❌ Non-compliant | Cannot process payments |
| **GDPR** | ✅ "Passed scans" | ❌ Non-compliant | Cannot handle EU data |
| **SOX** | ✅ "Passed scans" | ❌ Non-compliant | Cannot audit financials |
| **MiFID II** | ✅ "Passed scans" | ❌ Non-compliant | Cannot trade securities |

**Audit finding**: "Service passed automated scans but lacks basic access controls"

**Result**: Regulatory violations despite "clean" scan results.

---

## Conclusion

### Summary of Findings

| Metric | CodeQL | Aikido (Free) | Manual Review |
|--------|--------|---------------|---------------|
| **Vulnerabilities Found** | 0 | 0 | 8 |
| **Critical Issues** | 0 | 0 | 3 reported, 2 after verification |
| **False Negatives** | 8 of 8 (100%) | 8 of 8 (100%) | Unknown (no independent baseline) |
| **Deployment Recommendation** | ✅ Deploy | ✅ Deploy | 🔴 DO NOT DEPLOY |

---

### The Central Finding

**Three security assessments of the same codebase:**

1. **CodeQL**: "✅ No vulnerabilities - Safe to deploy"
2. **Aikido**: "✅ No vulnerabilities - Safe to deploy"
3. **Manual Review**: "🔴 3 CRITICAL vulnerabilities - DO NOT DEPLOY"

**They can't all be right.**

**Answer**: Manual review is the closest to the truth. Verification confirmed all 8 findings in the code, though with 2 CRITICAL issues rather than 3. The codebase had security vulnerabilities that:
- Let any logged-in customer read and trade on other customers' accounts (#3, CRITICAL)
- Let any pod inside the `mbd` namespace call the API without a JWT (#2, fixed)
- Disabled the Kafka type allow-list on an unauthenticated Kafka cluster, a potential code-execution path (#1, fixed; not demonstrated)
- Violate regulatory compliance requirements

**Automated tools gave a false sense of security.**

---

### Final Verdict

#### CodeQL
**Grade**: ⭐⭐⭐⭐ (4/5)
- Excellent for what it does (code-level patterns)
- Free and fast
- Essential CI/CD tool
- But insufficient alone

#### Aikido (Free Tier)
**Grade**: ⭐⭐⭐ (3/5)
- Good for dependency scanning
- Didn't outperform CodeQL on code analysis
- Configuration scanning didn't work as expected
- Free tier value proposition unclear

#### Aikido (AI Code Analysis - Paid)
**Grade**: ⭐⭐ (2/5) - Not tested, estimated
- Very expensive
- Likely still misses architectural gaps
- Not worth cost vs manual review
- Uncertain if it would find config issues

#### Manual OWASP Review
**Grade**: ⭐⭐⭐⭐⭐ (5/5)
- Found all 8 issues identified in this study (all confirmed in code; completeness untested)
- Only method that detected deployment blockers
- Some severities were overstated, so findings need verification
- Essential for production readiness
- Non-negotiable for financial services

---

### The Uncomfortable Truth

> **"Passing all automated security scans does not mean a system is secure."**

**This case study illustrates it.**

Two industry-leading security tools (CodeQL and Aikido) both reported **zero vulnerabilities**. A manual review found the service had **critical, deployment-blocking gaps**. These were confirmed in the code afterwards, but have not yet been demonstrated against a running system.

**Lesson**: Automated tools are necessary but insufficient. Manual security review is the only layer that found the critical issues.

---

## References

- **CodeQL Results**: [`CodeQL-OWASP-validation-result.md`](./CodeQL-OWASP-validation-result.md) (workflow: `.github/workflows/codeql-portfolio-service.yml`)
- **Aikido Results**: Aikido Dashboard (0 issues found)
- **Manual Review**: [`claude-OWASP-validation-result.md`](./claude-OWASP-validation-result.md)
- **Fix analyses per issue**: `Issue-1-…` to `Issue-8-…-Fix-Analysis.md` in this folder
- **Aikido Detailed Report**: `aikido-OWASP-validation-result.md`

---

*Generated: 2026-09-04*
*Analysis Type: Three-way comparative security assessment*
*Conclusion: Manual review is essential - automated tools alone are dangerously insufficient*
