# Aikido Security Scanning Setup Instructions
## Portfolio Service

**Created**: 2026-09-04
**Workflow**: `.github/workflows/aikido-portfolio-service.yml`

---

## Overview

Aikido is a comprehensive Application Security Platform that provides:
- **SAST**: Static code analysis for security vulnerabilities
- **SCA**: Software Composition Analysis (dependency vulnerability scanning)
- **Secrets Scanning**: Detection of hardcoded credentials and API keys
- **Container Scanning**: Docker image vulnerability analysis
- **Configuration Analysis**: YAML, properties, and config file security

**Key Advantage over CodeQL**: Aikido scans configuration files and dependencies, which CodeQL does not.

---

## Prerequisites

### 1. Aikido Account Setup

1. **Sign up for Aikido**:
   - Visit: https://www.aikido.dev
   - Create an account (free tier available)
   - Or contact Aikido for enterprise license

2. **Create an API Secret Key**:
   - Log in to Aikido dashboard
   - Go to **Settings** → **API Keys**
   - Click **Create New Key**
   - Give it a name: `GitHub Actions - MBD Portfolio Service`
   - Copy the generated secret key (you'll need this for GitHub)
   - **IMPORTANT**: Save this key securely - it won't be shown again

---

## GitHub Setup

### 2. Add Aikido Secret to GitHub Repository

1. **Navigate to Repository Settings**:
   ```
   GitHub Repository → Settings → Secrets and variables → Actions
   ```

2. **Create New Repository Secret**:
   - Click **"New repository secret"**
   - **Name**: `AIKIDO_SECRET_KEY`
   - **Value**: Paste the secret key from Aikido dashboard
   - Click **"Add secret"**

### 3. Verify Workflow File Exists

The workflow file should be at:
```
.github/workflows/aikido-portfolio-service.yml
```

If not, ensure it's committed to the repository.

---

## Running the Scan

### Manual Trigger (Default)

1. Go to **Actions** tab in GitHub
2. Select **"Aikido Security Analysis - Portfolio Service"**
3. Click **"Run workflow"** button
4. Select branch (usually `main`)
5. Click **"Run workflow"** (green button)

### Workflow Execution

The workflow will:
1. ✅ Checkout repository
2. ✅ Set up Java 17
3. ✅ Build portfolio-service
4. ✅ Run Aikido security scan
5. ✅ Generate summary report
6. ✅ Upload results as artifacts
7. ✅ Upload SARIF to GitHub Security (if available)

**Duration**: ~3-5 minutes

---

## Viewing Results

### Option 1: Aikido Dashboard (Recommended)

1. Log in to https://app.aikido.dev
2. Navigate to your repository/project
3. View detailed findings with:
   - Severity levels
   - Affected files and line numbers
   - Remediation advice
   - CVE details (for dependencies)
   - Fix suggestions

### Option 2: GitHub Security Tab

1. Go to **Security** → **Code scanning**
2. Filter by tool: **Aikido**
3. View alerts uploaded via SARIF

### Option 3: Workflow Artifacts

1. Go to **Actions** → Select the completed workflow run
2. Download **"aikido-scan-summary"** artifact
3. Open `aikido-scan-summary.md` for overview

---

## Expected Findings

Based on manual OWASP review, Aikido should detect:

### High Confidence Detections

✅ **Dependency Vulnerabilities** (if any)
- Scans `build.gradle.kts` for known CVEs
- Checks Spring Boot, Kotlin, and all dependencies
- Example: "Spring Boot 3.1.0 has CVE-2023-XXXXX"

✅ **Configuration Issues**
- Exposed actuator endpoints
- Debug logging enabled
- Insecure YAML settings
- Example: Should flag `spring.json.trusted.packages: "*"`

✅ **Secrets** (if any hardcoded)
- Database passwords
- API keys
- Private keys

### Medium Confidence

⚠️ **Code Patterns**
- May detect similar issues to CodeQL
- Could flag security anti-patterns
- Might identify missing input validation

### Low Confidence

❌ **Will Likely Miss** (same limitations as CodeQL):
- Missing authentication (architectural)
- Missing authorization (business logic)
- Race conditions across microservices

---

## Comparing Results

### Aikido vs CodeQL

After running both scans, compare:

| Finding | CodeQL | Aikido | Manual Review |
|---------|--------|--------|---------------|
| **Unsafe Kafka deserialization** | ❌ Missed | ✅ Should detect | ✅ Detected |
| **Actuator exposure** | ❌ Missed | ✅ Should detect | ✅ Detected |
| **Debug logging** | ❌ Missed | ⚠️ Maybe | ✅ Detected |
| **Vulnerable dependencies** | ❌ Not scanned | ✅ Should detect | ⚠️ Manual |
| **Missing authentication** | ❌ Missed | ❌ Will miss | ✅ Detected |
| **SQL injection** | ✅ Checked (none) | ✅ Should check | ✅ Verified safe |

**Expected Outcome**: Aikido should find 1-2 issues that CodeQL missed, particularly the configuration vulnerabilities.

---

## Integration with Existing Security Workflows

### Layered Security Approach

```
┌─────────────────────────────────────────────────────────┐
│  Layer 1: CodeQL (SAST - Code vulnerabilities)         │
│  ✅ SQL injection, XSS, command injection               │
│  ❌ Configuration, dependencies                         │
└─────────────────────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│  Layer 2: Aikido (SAST + SCA + Config + Secrets)       │
│  ✅ Code vulnerabilities                                │
│  ✅ Configuration issues                                │
│  ✅ Dependency CVEs                                     │
│  ✅ Hardcoded secrets                                   │
│  ❌ Business logic, architecture                        │
└─────────────────────────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────┐
│  Layer 3: Manual OWASP Review                           │
│  ✅ Architecture validation                             │
│  ✅ Business logic security                             │
│  ✅ Missing security controls                           │
│  ✅ Compliance requirements                             │
└─────────────────────────────────────────────────────────┘
```

### Recommended Workflow

1. **During Development**:
   - Run CodeQL on every PR (fast, catches common issues)

2. **Before Deployment**:
   - Run Aikido (comprehensive scan including dependencies)
   - Run manual security review (architecture validation)

3. **Regular Maintenance**:
   - Weekly Aikido scans (catch new CVEs in dependencies)
   - Monthly manual reviews (verify controls still effective)

---

## Troubleshooting

### Issue: "Authentication failed"

**Cause**: Invalid or missing `AIKIDO_SECRET_KEY`

**Solution**:
1. Verify secret exists in GitHub: Settings → Secrets
2. Regenerate key in Aikido dashboard
3. Update GitHub secret with new key

### Issue: "No findings reported"

**Cause**: Aikido may not support Kotlin/Gradle or scan completed successfully

**Solution**:
1. Check Aikido dashboard for results
2. Verify language support at https://docs.aikido.dev
3. Check workflow logs for errors

### Issue: "SARIF upload failed"

**Cause**: Aikido didn't generate SARIF file or format incompatible

**Solution**:
1. Check if `aikido-results.sarif` exists in workflow
2. Set `continue-on-error: true` (already configured)
3. View results in Aikido dashboard instead

### Issue: "Workflow failed on build step"

**Cause**: Portfolio-service build failure

**Solution**:
1. Fix build errors first
2. Run `./gradlew :portfolio-service:build` locally
3. Commit fixes and re-run workflow

---

## Cost Considerations

### Aikido Pricing (as of 2026)

- **Free Tier**:
  - Limited scans per month
  - Community support
  - Basic features

- **Pro Tier**:
  - Unlimited scans
  - All security features
  - Priority support
  - Check https://www.aikido.dev/pricing for current rates

- **Enterprise**:
  - Custom pricing
  - Dedicated support
  - SSO, compliance reports

**Recommendation**: Start with free tier to evaluate effectiveness.

---

## Advanced Configuration

### Customizing Scan Scope

Edit `.github/workflows/aikido-portfolio-service.yml`:

```yaml
# Scan specific paths only
paths: |
  backend/portfolio-service/src
  backend/portfolio-service/build.gradle.kts

# Exclude paths
exclude-paths: |
  backend/portfolio-service/src/test
  backend/portfolio-service/build

# Set severity threshold (fail workflow if issues found)
severity-threshold: high  # Options: low, medium, high, critical

# Fail build on timeout
fail-on-timeout: true
```

### Scheduled Scans

Add to workflow `on:` section:

```yaml
on:
  workflow_dispatch: {}
  schedule:
    - cron: '0 2 * * 1'  # Run every Monday at 2 AM UTC
```

### Scan All Services

Modify `paths:` to scan entire backend:

```yaml
paths: backend/
```

---

## Success Criteria

### What Success Looks Like

After setup, you should have:

✅ **Aikido workflow successfully runs** (green checkmark)
✅ **Results appear in Aikido dashboard** (with findings)
✅ **Summary artifact generated** (downloadable report)
✅ **Configuration issues detected** (validates Aikido works better than CodeQL for config)

### What to Do Next

1. **Review Aikido findings** against manual review
2. **Fix critical configuration issues** (especially Kafka deserialization)
3. **Update comparison document** with actual Aikido results
4. **Establish regular scanning schedule** (weekly or on PR)

---

## Additional Resources

### Documentation
- Aikido Docs: https://docs.aikido.dev
- GitHub Actions Integration: https://docs.aikido.dev/integrations/github-actions
- OWASP Top 10: https://owasp.org/www-project-top-ten/

### Support
- Aikido Support: support@aikido.dev
- GitHub Discussions: https://github.com/aikidosec/github-actions-workflow/discussions
- Aikido Community: https://community.aikido.dev

---

## Maintenance

### Regular Tasks

- **Monthly**: Review and update Aikido workflow configuration
- **Quarterly**: Compare Aikido vs CodeQL effectiveness
- **Yearly**: Evaluate Aikido subscription tier needs

### Keep Updated

Watch for:
- Aikido GitHub Action updates (`aikidosec/github-actions-workflow@v2`, etc.)
- New Aikido features (runtime protection, API security)
- Changes to security best practices

---

*This setup guide ensures comprehensive security scanning beyond what CodeQL provides, particularly for configuration and dependency vulnerabilities.*

*Last Updated: 2026-09-04*
