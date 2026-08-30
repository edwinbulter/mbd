# SBOM Generation and Vulnerability Scanning

This guide describes how to generate Software Bill of Materials (SBOM) for MBD backend services and scan them for vulnerabilities using Grype.

---

## Table of Contents

- [What is an SBOM?](#what-is-an-sbom)
- [Why Generate SBOMs?](#why-generate-sboms)
- [SBOM Generation with CycloneDX](#sbom-generation-with-cyclonedx)
  - [Configuration](#configuration)
  - [Generating the SBOM](#generating-the-sbom)
  - [SBOM Output](#sbom-output)
- [Vulnerability Scanning with Grype](#vulnerability-scanning-with-grype)
  - [Installing Grype](#installing-grype)
  - [Scanning the SBOM](#scanning-the-sbom)
  - [Understanding the Output](#understanding-the-output)
  - [Output Formats](#output-formats)
- [Applying to Other Services](#applying-to-other-services)
- [CI/CD Integration](#cicd-integration)
- [Troubleshooting](#troubleshooting)

---

## What is an SBOM?

A Software Bill of Materials (SBOM) is a comprehensive inventory of all components, libraries, and dependencies used in a software application. It's similar to an ingredients list on food packaging, but for software.

An SBOM typically includes:
- Component names and versions
- Package URLs (PURL)
- Cryptographic hashes
- License information
- Dependency relationships

---

## Why Generate SBOMs?

1. **Security**: Quickly identify vulnerable dependencies in your software supply chain
2. **Compliance**: Many regulations (e.g., Executive Order 14028) require SBOMs
3. **Transparency**: Understand what's in your software and where it comes from
4. **Vulnerability Management**: Rapidly respond to newly discovered vulnerabilities (e.g., Log4Shell)
5. **License Compliance**: Track open-source licenses in your dependencies

---

## SBOM Generation with CycloneDX

We use **CycloneDX** to generate SBOMs for our backend services. CycloneDX is an industry-standard SBOM format maintained by OWASP.

### Configuration

The `account-service` is already configured to generate SBOMs. Here's the configuration in `backend/account-service/build.gradle.kts`:

```kotlin
plugins {
    id("org.springframework.boot") version "3.1.0"
    id("io.spring.dependency-management") version "1.1.0"
    kotlin("jvm") version "1.9.20"
    kotlin("plugin.spring") version "1.9.20"
    kotlin("plugin.jpa") version "1.9.20"
    id("org.cyclonedx.bom") version "1.8.2"  // CycloneDX plugin
}

// ... dependencies ...

tasks.cyclonedxBom {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setSchemaVersion("1.5")
    setDestination(file("build/reports"))
    setOutputName("bom")
    setOutputFormat("json")
    setIncludeBomSerialNumber(true)
    setIncludeLicenseText(false)
    setComponentVersion(version.toString())
}
```

**Configuration Breakdown:**
- `setIncludeConfigs`: Only include runtime dependencies (excludes test dependencies)
- `setSchemaVersion`: Use CycloneDX schema version 1.5
- `setDestination`: Output directory for the SBOM
- `setOutputName`: Name of the output file (will be `bom.json`)
- `setOutputFormat`: Generate JSON format (also supports XML)
- `setIncludeBomSerialNumber`: Include a unique serial number for tracking
- `setComponentVersion`: Include the service version in the SBOM

### Generating the SBOM

To generate the SBOM for `account-service`:

```bash
cd backend
./gradlew :account-service:cyclonedxBom
```

**Output:**
```
> Task :account-service:cyclonedxBom

BUILD SUCCESSFUL in 3s
1 actionable task: 1 executed
```

### SBOM Output

The SBOM is generated at: `backend/account-service/build/reports/bom.json`

**File Statistics:**
- Size: ~253KB
- Components: 113 runtime dependencies
- Format: CycloneDX 1.5 JSON

**Sample SBOM Structure:**
```json
{
  "bomFormat": "CycloneDX",
  "specVersion": "1.5",
  "serialNumber": "urn:uuid:edd66184-23fa-4f9b-8cc4-23175e5b5cb7",
  "version": 1,
  "metadata": {
    "timestamp": "2026-08-30T12:16:39Z",
    "component": {
      "group": "com.mbd",
      "name": "account-service",
      "version": "0.0.1-SNAPSHOT",
      "purl": "pkg:maven/com.mbd/account-service@0.0.1-SNAPSHOT?type=jar",
      "type": "library"
    }
  },
  "components": [
    {
      "group": "org.springframework.boot",
      "name": "spring-boot-starter-web",
      "version": "3.1.0",
      "hashes": [
        {
          "alg": "SHA-256",
          "content": "..."
        }
      ],
      "purl": "pkg:maven/org.springframework.boot/spring-boot-starter-web@3.1.0"
    }
    // ... 112 more components
  ]
}
```

**Viewing Component Count:**
```bash
jq '.components | length' backend/account-service/build/reports/bom.json
```

**Viewing Component List:**
```bash
jq '.components[] | "\(.group):\(.name):\(.version)"' backend/account-service/build/reports/bom.json
```

---

## Vulnerability Scanning with Grype

**Grype** is a vulnerability scanner from Anchore that can scan SBOMs for known vulnerabilities. It uses multiple vulnerability databases including:
- NVD (National Vulnerability Database)
- GitHub Security Advisories
- Alpine SecDB
- Red Hat Security Data
- And more...

### Installing Grype

**macOS (using Homebrew):**
```bash
brew tap anchore/grype
brew install grype
```

**Verify Installation:**
```bash
grype version
```

Example output:
```
Application:         grype
Version:             0.XX.X
Syft Version:        v0.XX.X
BuildDate:           2024-XX-XX
GitCommit:           xxxxxxxxxx
Platform:            darwin/arm64
```

### Scanning the SBOM

**Basic Scan (from project root):**
```bash
grype sbom:backend/account-service/build/reports/bom.json
```

Example output:
```
 ✔ Scanned for vulnerabilities     [88 vulnerability matches]
   ├── by severity: 5 critical, 38 high, 35 medium, 10 low, 0 negligible
   └── by status:   71 fixed, 17 not-fixed, 0 ignored

NAME                    INSTALLED  FIXED-IN  TYPE          VULNERABILITY        SEVERITY
tomcat-embed-core       10.1.8     10.1.35   java-archive  CVE-2025-24813       Critical
snakeyaml               1.33       2.0       java-archive  CVE-2022-1471        High
postgresql              42.6.0     42.6.1    java-archive  CVE-2024-1597        Critical
spring-webmvc           6.0.9      6.0.17    java-archive  CVE-2024-22243       High
...
```

**Scan with Specific Output Format:**
```bash
# JSON output (for programmatic processing)
grype sbom:backend/account-service/build/reports/bom.json -o json

# Table output (default, human-readable)
grype sbom:backend/account-service/build/reports/bom.json -o table

# SARIF output (for GitHub Security integration)
grype sbom:backend/account-service/build/reports/bom.json -o sarif
```

**Scan and Fail on Severity Threshold:**
```bash
# Fail build if HIGH or CRITICAL vulnerabilities are found
grype sbom:backend/account-service/build/reports/bom.json --fail-on high

# Fail build if CRITICAL vulnerabilities are found
grype sbom:backend/account-service/build/reports/bom.json --fail-on critical

# Note: Exit code will be non-zero if vulnerabilities at or above threshold are found
```

**Save Results to File:**
```bash
# Save as JSON (with quiet mode to suppress progress output)
grype sbom:backend/account-service/build/reports/bom.json -o json -q > backend/account-service/build/reports/vulnerabilities.json

# Save as SARIF (for GitHub)
grype sbom:backend/account-service/build/reports/bom.json -o sarif > vulnerabilities.sarif
```

### Understanding the Output

**Sample Grype Output (Table Format):**
```
NAME                    INSTALLED  FIXED-IN  TYPE  VULNERABILITY   SEVERITY
spring-boot-starter-web 3.1.0      3.1.5     java  CVE-2023-34035  High
hibernate-core          6.2.2      6.2.13    java  CVE-2024-23672  Medium
postgresql              42.5.1     42.5.4    java  CVE-2024-1597   Medium
```

**Column Breakdown:**
- **NAME**: The vulnerable component name
- **INSTALLED**: Currently installed version
- **FIXED-IN**: Version where the vulnerability is fixed
- **TYPE**: Package type (java, npm, python, etc.)
- **VULNERABILITY**: CVE identifier or vulnerability ID
- **SEVERITY**: Severity level (Critical, High, Medium, Low, Negligible)

**JSON Output Structure:**
```json
{
  "matches": [
    {
      "vulnerability": {
        "id": "CVE-2023-34035",
        "severity": "High",
        "description": "Spring Security vulnerable to authorization bypass...",
        "urls": ["https://nvd.nist.gov/vuln/detail/CVE-2023-34035"]
      },
      "artifact": {
        "name": "spring-boot-starter-web",
        "version": "3.1.0",
        "type": "java-archive",
        "purl": "pkg:maven/org.springframework.boot/spring-boot-starter-web@3.1.0"
      },
      "matchDetails": [
        {
          "found": {
            "versionConstraint": "< 3.1.5"
          },
          "searchedBy": {
            "namespace": "nvd:cpe"
          }
        }
      ]
    }
  ]
}
```

### Output Formats

Grype supports multiple output formats:

| Format | Description | Use Case |
|--------|-------------|----------|
| `table` | Human-readable table (default) | CLI viewing, quick inspection |
| `json` | Structured JSON | Programmatic processing, CI/CD integration |
| `cyclonedx` | CycloneDX VEX format | Vulnerability exchange, SBOM tooling |
| `sarif` | SARIF format | GitHub Security tab, IDE integration |

**Examples:**
```bash
# Table format (default)
grype sbom:backend/account-service/build/reports/bom.json

# JSON format for automation
grype sbom:backend/account-service/build/reports/bom.json -o json

# SARIF for GitHub Security
grype sbom:backend/account-service/build/reports/bom.json -o sarif

# CycloneDX VEX format
grype sbom:backend/account-service/build/reports/bom.json -o cyclonedx
```

---

## Applying to Other Services

To add SBOM generation to other backend services (user-service, fund-service, portfolio-service, admin-service):

### Step 1: Add CycloneDX Plugin

Add to the service's `build.gradle.kts`:

```kotlin
plugins {
    // ... existing plugins ...
    id("org.cyclonedx.bom") version "1.8.2"
}
```

### Step 2: Add Configuration

Add at the end of `build.gradle.kts`:

```kotlin
tasks.cyclonedxBom {
    setIncludeConfigs(listOf("runtimeClasspath"))
    setSchemaVersion("1.5")
    setDestination(file("build/reports"))
    setOutputName("bom")
    setOutputFormat("json")
    setIncludeBomSerialNumber(true)
    setIncludeLicenseText(false)
    setComponentVersion(version.toString())
}
```

### Step 3: Generate SBOM

```bash
cd backend
./gradlew :user-service:cyclonedxBom
./gradlew :fund-service:cyclonedxBom
./gradlew :portfolio-service:cyclonedxBom
./gradlew :admin-service:cyclonedxBom
```

### Step 4: Scan All Services

```bash
# Scan all service SBOMs
for service in user-service account-service fund-service portfolio-service admin-service; do
  echo "Scanning $service..."
  grype sbom:backend/$service/build/reports/bom.json -o json > backend/$service/build/reports/vulnerabilities.json
done
```

### Generate All SBOMs at Once

```bash
cd backend
./gradlew cyclonedxBom
```

This generates SBOMs for all services that have the plugin configured.

---

## CI/CD Integration

### GitHub Actions Example

Create `.github/workflows/sbom-scan.yml`:

```yaml
name: SBOM Generation and Vulnerability Scan

on:
  push:
    branches: [ main ]
    paths:
      - 'backend/**'
  pull_request:
    branches: [ main ]
    paths:
      - 'backend/**'

jobs:
  sbom-and-scan:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v3

      - name: Set up JDK 17
        uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Generate SBOMs
        run: |
          cd backend
          ./gradlew cyclonedxBom

      - name: Install Grype
        run: |
          curl -sSfL https://raw.githubusercontent.com/anchore/grype/main/install.sh | sh -s -- -b /usr/local/bin

      - name: Scan for Vulnerabilities
        run: |
          for service in user-service account-service fund-service portfolio-service admin-service; do
            echo "Scanning $service..."
            grype sbom:backend/$service/build/reports/bom.json -o sarif > $service-vulnerabilities.sarif
          done

      - name: Upload SARIF Results
        uses: github/codeql-action/upload-sarif@v2
        with:
          sarif_file: '*-vulnerabilities.sarif'

      - name: Fail on High/Critical Vulnerabilities
        run: |
          grype sbom:backend/account-service/build/reports/bom.json --fail-on high
```

### GitLab CI Example

Create `.gitlab-ci.yml`:

```yaml
sbom-scan:
  stage: test
  image: gradle:7.6-jdk17
  before_script:
    - curl -sSfL https://raw.githubusercontent.com/anchore/grype/main/install.sh | sh -s -- -b /usr/local/bin
  script:
    - cd backend
    - ./gradlew cyclonedxBom
    - |
      for service in user-service account-service fund-service portfolio-service admin-service; do
        grype sbom:backend/$service/build/reports/bom.json -o json > $service-vulnerabilities.json
      done
  artifacts:
    reports:
      cyclonedx: backend/**/build/reports/bom.json
    paths:
      - "*-vulnerabilities.json"
```

---

## Troubleshooting

### Issue: SBOM Generation Fails

**Error:**
```
> Task :account-service:cyclonedxBom FAILED
Could not resolve all dependencies
```

**Solution:**
Ensure all dependencies are properly configured in `build.gradle.kts` and can be resolved from Maven Central.

```bash
# Check dependency resolution
./gradlew :account-service:dependencies
```

---

### Issue: Grype Database Update Fails

**Error:**
```
unable to update vulnerability database
```

**Solution:**
Manually update the Grype database:

```bash
grype db update
```

Or use a cached database in CI/CD:

```yaml
- name: Cache Grype DB
  uses: actions/cache@v3
  with:
    path: ~/.cache/grype
    key: grype-db-${{ github.run_id }}
    restore-keys: grype-db-
```

---

### Issue: Too Many False Positives

**Solution:**
Create a `.grype.yaml` configuration file to ignore specific vulnerabilities:

```yaml
# .grype.yaml
ignore:
  # Ignore specific CVEs
  - vulnerability: CVE-2023-12345
    reason: "Not applicable - we don't use the vulnerable feature"

  # Ignore by package
  - package:
      name: "some-package"
      version: "1.0.0"
    reason: "Mitigated by network segmentation"

  # Ignore by severity
  - severity: Negligible
```

Use the config:

```bash
grype sbom:backend/account-service/build/reports/bom.json --config .grype.yaml
```

---

### Issue: Different Vulnerability Counts Between Runs

**Explanation:**
Grype's vulnerability database is constantly updated. Running the same scan on different days may yield different results as new vulnerabilities are discovered.

**Solution:**
This is expected behavior. Regular scanning helps you stay up-to-date with the latest vulnerability information.

---

## Best Practices

1. **Generate SBOMs Regularly**: Include SBOM generation in your CI/CD pipeline
2. **Scan Before Deployment**: Always scan SBOMs for vulnerabilities before deploying to production
3. **Set Severity Thresholds**: Fail builds on HIGH or CRITICAL vulnerabilities
4. **Keep Dependencies Updated**: Regularly update dependencies to fix known vulnerabilities
5. **Archive SBOMs**: Store SBOMs with release artifacts for compliance and auditing
6. **Monitor Continuously**: Set up automated scanning to detect newly discovered vulnerabilities in deployed applications
7. **Document Exceptions**: When ignoring vulnerabilities, document the reason and mitigation strategy

---

## Additional Resources

- [CycloneDX Official Site](https://cyclonedx.org/)
- [Grype Documentation](https://github.com/anchore/grype)
- [OWASP SBOM Forum](https://owasp.org/www-community/Component_Analysis)
- [CISA SBOM Resources](https://www.cisa.gov/sbom)
- [Executive Order 14028](https://www.whitehouse.gov/briefing-room/presidential-actions/2021/05/12/executive-order-on-improving-the-nations-cybersecurity/)

---

## Summary

This guide covered:
- ✅ What SBOMs are and why they matter
- ✅ How to generate SBOMs with CycloneDX for account-service
- ✅ How to install and use Grype for vulnerability scanning
- ✅ How to apply SBOM generation to other services
- ✅ CI/CD integration examples
- ✅ Troubleshooting common issues

**Next Steps:**
1. Apply SBOM generation to all backend services
2. Integrate Grype scanning into your CI/CD pipeline
3. Set up automated alerts for new vulnerabilities
4. Create a process for addressing identified vulnerabilities
