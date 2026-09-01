# Istio Rate Limiting

## Overview

This document describes the implementation of infrastructure-level rate limiting in the MBD application using Istio's Envoy proxy sidecar pattern. Rate limiting is a critical security control that protects backend services from:

- **Denial of Service (DoS) attacks** - Overwhelming services with excessive requests
- **Brute force attacks** - Rapid enumeration or authentication attempts
- **API abuse** - Excessive usage that degrades service quality
- **Financial manipulation** - Exploiting race conditions through rapid-fire transactions

## Architecture

### How It Works

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ HTTP Request
       ▼
┌─────────────────────────────────────┐
│     Istio Ingress Gateway           │
│  (Initial traffic entry point)      │
└──────┬──────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────┐
│   Service Pod (e.g., account-service)│
│  ┌───────────────────────────────┐  │
│  │   Envoy Sidecar Proxy         │  │
│  │  ┌─────────────────────────┐  │  │
│  │  │ Rate Limit Filter       │  │  │
│  │  │ (Token Bucket Algorithm)│  │  │
│  │  └──────────┬──────────────┘  │  │
│  │             │                  │  │
│  │   ┌─────────▼─────────────┐   │  │
│  │   │  Allowed? (HTTP 200)  │   │  │
│  │   │  Denied?  (HTTP 429)  │   │  │
│  │   └─────────┬─────────────┘   │  │
│  └─────────────┼─────────────────┘  │
│                │ (if allowed)        │
│        ┌───────▼──────────┐         │
│        │ Application      │         │
│        │ Container        │         │
│        └──────────────────┘         │
└─────────────────────────────────────┘
```

### Token Bucket Algorithm

Istio's local rate limiter uses the **token bucket algorithm**:

1. **Bucket Capacity**: `max_tokens` defines the maximum burst size
2. **Refill Rate**: `tokens_per_fill` tokens are added every `fill_interval`
3. **Token Consumption**: Each request consumes 1 token
4. **Rejection**: If bucket is empty, request is denied with HTTP 429

**Example Configuration:**
```yaml
token_bucket:
  max_tokens: 100          # Bucket capacity
  tokens_per_fill: 100     # Tokens added per refill
  fill_interval: 60s       # Refill every 60 seconds
```

**Behavior:**
- Allows bursts of up to 100 requests
- Sustains 100 requests per minute (1.67 requests/second)
- Bucket refills completely every 60 seconds
- Empty bucket = HTTP 429 Too Many Requests

## Implementation

### EnvoyFilter Configuration

Rate limiting is implemented via Istio `EnvoyFilter` custom resources that inject rate limiting HTTP filters into the Envoy sidecar proxy.

**File:** `infrastructure/k8s/istio/rate-limiting.yaml`

### Service-Specific Limits

| Service | Limit (req/min) | Burst | Rationale |
|---------|----------------|-------|-----------|
| **account-service** | 100 | 100 | Financial operations require moderate throughput |
| **portfolio-service** | 50 | 50 | Trading operations need stricter limits to prevent abuse |
| **fund-service** | 100 | 100 | Read-heavy catalog queries, higher throughput acceptable |
| **user-service** | 100 | 100 | Authentication and profile operations, moderate limit |
| **admin-service** | 50 | 50 | Administrative operations should be infrequent |

### Configuration Template

```yaml
apiVersion: networking.istio.io/v1alpha3
kind: EnvoyFilter
metadata:
  name: <service-name>-rate-limit
  namespace: mbd
spec:
  workloadSelector:
    labels:
      app: <service-name>
  configPatches:
  - applyTo: HTTP_FILTER
    match:
      context: SIDECAR_INBOUND
      listener:
        filterChain:
          filter:
            name: "envoy.filters.network.http_connection_manager"
            subFilter:
              name: "envoy.filters.http.router"
    patch:
      operation: INSERT_BEFORE
      value:
        name: envoy.filters.http.local_ratelimit
        typed_config:
          "@type": type.googleapis.com/envoy.extensions.filters.http.local_ratelimit.v3.LocalRateLimit
          stat_prefix: http_local_rate_limiter
          token_bucket:
            max_tokens: 100
            tokens_per_fill: 100
            fill_interval: 60s
          filter_enabled:
            runtime_key: local_rate_limit_enabled
            default_value:
              numerator: 100
              denominator: HUNDRED
          filter_enforced:
            runtime_key: local_rate_limit_enforced
            default_value:
              numerator: 100
              denominator: HUNDRED
          response_headers_to_add:
          - append: false
            header:
              key: x-local-rate-limit
              value: 'true'
          status:
            code: 429
```

## Deployment

### Apply Rate Limiting

```bash
# Apply EnvoyFilter configurations
kubectl apply -f infrastructure/k8s/istio/rate-limiting.yaml

# Verify EnvoyFilters are created
kubectl get envoyfilter -n mbd
```

**Expected Output:**
```
NAME                            AGE
account-service-rate-limit      1m
portfolio-service-rate-limit    1m
user-service-rate-limit         1m
fund-service-rate-limit         1m
admin-service-rate-limit        1m
```

### Verify Configuration

```bash
# Check Envoy configuration in sidecar
kubectl exec -n mbd <pod-name> -c istio-proxy -- \
  curl localhost:15000/config_dump | grep -A 20 local_ratelimit
```

## Testing

### Basic Rate Limit Test

```bash
# Test account-service rate limit (100 req/min)
for i in {1..110}; do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "Authorization: Bearer <token>" \
    https://customer.mbd.local/api/accounts/1
done
```

**Expected Output:**
```
200  # First 100 requests succeed
200
...
200
429  # Requests 101-110 are rate limited
429
...
```

### Check Rate Limit Headers

```bash
# Check for rate limit response header
curl -I https://customer.mbd.local/api/accounts/1 \
  -H "Authorization: Bearer <token>"
```

**Response (when rate limited):**
```http
HTTP/1.1 429 Too Many Requests
x-local-rate-limit: true
content-length: 18
content-type: text/plain
date: Mon, 01 Sep 2026 10:00:00 GMT
server: istio-envoy
```

### Load Testing Script

```bash
#!/bin/bash
# File: test-rate-limit.sh
# Purpose: Validate rate limiting configuration

SERVICE="https://customer.mbd.local/api/accounts/1"
TOKEN="<your-bearer-token>"
TOTAL_REQUESTS=120
SUCCESS=0
RATE_LIMITED=0

echo "Testing rate limit with $TOTAL_REQUESTS requests..."

for i in $(seq 1 $TOTAL_REQUESTS); do
  HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer $TOKEN" \
    "$SERVICE")

  if [ "$HTTP_CODE" == "200" ]; then
    ((SUCCESS++))
  elif [ "$HTTP_CODE" == "429" ]; then
    ((RATE_LIMITED++))
  fi

  # Small delay to avoid instant burst
  sleep 0.1
done

echo "Results:"
echo "  Success (200): $SUCCESS"
echo "  Rate Limited (429): $RATE_LIMITED"
echo "  Expected: ~100 success, ~20 rate limited"
```

## Monitoring

### Envoy Metrics

Rate limiting metrics are exposed by the Envoy sidecar on port 15020:

```bash
# View rate limit metrics
kubectl exec -n mbd <pod-name> -c istio-proxy -- \
  curl -s localhost:15020/stats/prometheus | grep rate_limit
```

**Key Metrics:**
```
# Requests allowed
envoy_local_rate_limit_ok{stat_prefix="http_local_rate_limiter"}

# Requests rate limited
envoy_local_rate_limit_rate_limited{stat_prefix="http_local_rate_limiter"}

# Token bucket state
envoy_local_rate_limit_token_bucket_fill_timer_count
```

### Prometheus Queries

```promql
# Rate limit rejection rate (%)
rate(envoy_local_rate_limit_rate_limited[5m]) /
rate(envoy_local_rate_limit_enabled[5m]) * 100

# Total rate limited requests per service
sum(rate(envoy_local_rate_limit_rate_limited[5m])) by (app)

# Token bucket utilization
1 - (envoy_local_rate_limit_token_bucket_tokens / max_tokens)
```

### Grafana Dashboard

Create alerts for excessive rate limiting:

```yaml
# Alert when >10% of requests are rate limited
alert: HighRateLimitRejectionRate
expr: |
  rate(envoy_local_rate_limit_rate_limited[5m]) /
  rate(envoy_local_rate_limit_enabled[5m]) > 0.1
for: 5m
labels:
  severity: warning
annotations:
  summary: "High rate limit rejection rate on {{ $labels.app }}"
  description: "{{ $value | humanizePercentage }} of requests are being rate limited"
```

## Security Benefits

### OWASP Top 10 2025 Coverage

**A07:2025 - Identification and Authentication Failures**
- ✅ **HIGH severity finding resolved** (A07-001 from OWASP validation report)
- Prevents brute force attacks on authentication endpoints
- Limits account enumeration attempts
- Mitigates credential stuffing attacks

**A04:2025 - Insecure Design**
- Prevents exploitation of race conditions through rapid-fire requests
- Limits ability to exploit timing-based vulnerabilities
- Reduces risk of concurrent transaction manipulation

**DoS Protection**
- Prevents service degradation from excessive legitimate or malicious traffic
- Protects downstream databases from connection pool exhaustion
- Ensures fair resource allocation across users

### Attack Mitigation Examples

#### Before Rate Limiting
```bash
# Attacker can enumerate all account IDs
for i in {1..10000}; do
  curl https://customer.mbd.local/api/accounts/$i -H "Auth: Bearer $TOKEN"
done
# All 10,000 requests succeed - full account database exposed
```

#### After Rate Limiting
```bash
# Same attack attempt
for i in {1..10000}; do
  curl https://customer.mbd.local/api/accounts/$i -H "Auth: Bearer $TOKEN"
done
# First 100 requests succeed, remaining 9,900 return HTTP 429
# Attacker learns only 100 accounts per minute (severely limited)
```

## Tuning Guidelines

### Adjusting Limits

**Increase limits** if legitimate users are being rate limited:
```yaml
token_bucket:
  max_tokens: 200      # Double the limit
  tokens_per_fill: 200
  fill_interval: 60s
```

**Decrease limits** for stricter protection:
```yaml
token_bucket:
  max_tokens: 50       # Halve the limit
  tokens_per_fill: 50
  fill_interval: 60s
```

### Per-Endpoint Rate Limiting

For more granular control, configure rate limits per endpoint pattern:

```yaml
# Example: Stricter limit for deposit endpoint
- applyTo: HTTP_ROUTE
  match:
    context: SIDECAR_INBOUND
    routeConfiguration:
      vhost:
        route:
          name: "default"
  patch:
    operation: MERGE
    value:
      route:
        rate_limits:
        - actions:
          - header_value_match:
              descriptor_value: "deposit"
              headers:
              - name: ":path"
                exact_match: "/api/accounts/:id/deposit"
```

## Limitations

### Local vs. Global Rate Limiting

**Current Implementation: Local Rate Limiting**
- Limits are enforced **per pod instance**
- Multiple replicas = multiple token buckets
- 3 account-service replicas with 100 req/min = 300 req/min total

**Calculation:**
```
Total Allowed = (requests_per_pod) × (number_of_replicas)
```

**Example:**
```
account-service:
  replicas: 3
  rate_limit: 100 req/min per pod
  total_capacity: 300 req/min
```

### Global Rate Limiting (Not Implemented)

For true global limits across all replicas, use Istio's global rate limit service:

```yaml
# Requires additional Redis/Memcached deployment
apiVersion: v1
kind: ConfigMap
metadata:
  name: ratelimit-config
data:
  config.yaml: |
    domain: mbd-ratelimit
    descriptors:
    - key: generic_key
      value: account-service
      rate_limit:
        unit: minute
        requests_per_unit: 100
```

**Recommendation:** For the MBD demo application, local rate limiting provides sufficient protection. Global rate limiting adds complexity and infrastructure overhead suitable for production deployments with many replicas.

## Troubleshooting

### Rate Limiting Not Working

**1. Verify EnvoyFilter is applied:**
```bash
kubectl get envoyfilter -n mbd <service-name>-rate-limit
kubectl describe envoyfilter -n mbd <service-name>-rate-limit
```

**2. Check Envoy configuration:**
```bash
kubectl exec -n mbd <pod-name> -c istio-proxy -- \
  curl localhost:15000/config_dump > envoy-config.json

# Search for local_ratelimit configuration
cat envoy-config.json | jq '.configs[] | select(.["@type"] == "type.googleapis.com/envoy.admin.v3.ListenersConfigDump")' | grep -A 50 local_ratelimit
```

**3. Verify metrics:**
```bash
# Should show non-zero values after requests
kubectl exec -n mbd <pod-name> -c istio-proxy -- \
  curl localhost:15020/stats/prometheus | grep local_rate_limit
```

### Unexpected 429 Responses

**1. Check if limit is too aggressive:**
```bash
# Review current configuration
kubectl get envoyfilter -n mbd <service-name>-rate-limit -o yaml
```

**2. Verify request patterns:**
```bash
# Check application logs for request frequency
kubectl logs -n mbd -l app=<service-name> --tail=100
```

**3. Consider increasing limits:**
```bash
# Edit EnvoyFilter
kubectl edit envoyfilter -n mbd <service-name>-rate-limit

# Modify token_bucket values and save
```

## References

- [Istio Rate Limiting Documentation](https://istio.io/latest/docs/tasks/policy-enforcement/rate-limit/)
- [Envoy Local Rate Limiting](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/local_rate_limit_filter)
- [OWASP API Security Top 10](https://owasp.org/www-project-api-security/)
- [Token Bucket Algorithm](https://en.wikipedia.org/wiki/Token_bucket)
- MBD OWASP Validation Report: `doc/OWASP-validation-result-account-service-2.md`

## Changelog

| Date | Version | Changes |
|------|---------|---------|
| 2026-09-01 | 1.0 | Initial implementation of rate limiting for all backend services |

---

**Document Owner:** DevSecOps Team
**Last Updated:** 2026-09-01
**Review Frequency:** Quarterly
