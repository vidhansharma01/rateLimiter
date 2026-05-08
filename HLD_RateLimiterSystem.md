# 🚦 High-Level Design (HLD) — Rate Limiter System
> **Target Level:** SDE 3 / Principal Engineer  
> **Interview Focus:** Algorithms (Token Bucket, Sliding Window), Distributed Rate Limiting, Redis, Consistency, Failure Handling

---

## 1. Requirements

### 1.1 Functional Requirements
- Limit the number of requests a **client (user / IP / API key)** can make to an API within a time window.
- Support **multiple rate limit rules** per client (e.g., 100 req/min AND 1000 req/hour).
- Support **different limits per API endpoint** (e.g., `/login` → 5/min, `/search` → 200/min).
- Support **global limits** (across all clients) and **per-client limits**.
- Return `429 Too Many Requests` with `Retry-After` header when limit is breached.
- Rules are **configurable dynamically** without service restart.
- Support **whitelisting** (internal services bypass rate limits).
- Rate limiter must work in a **distributed** environment (multiple servers behind a load balancer).

### 1.2 Non-Functional Requirements
| Property | Target |
|---|---|
| **Latency overhead** | < 5 ms per request (rate limiter check must be fast) |
| **Availability** | 99.99% — rate limiter failure should fail open (not block all traffic) |
| **Accuracy** | Allow at most X% burst above limit (< 1% overage acceptable) |
| **Throughput** | Handle 1M+ requests/sec across all clients |
| **Scalability** | Horizontally scalable; no single point of failure |
| **Consistency** | Near-real-time across distributed nodes (eventual consistency acceptable) |

### 1.3 Out of Scope
- Circuit breaker / retry logic (client responsibility)
- Billing based on API usage
- DDoS protection (separate layer — WAF/CDN)

---

## 2. Capacity Estimation

```
Total API requests        = 1 billion/day → ~12,000 req/sec avg, ~1M req/sec peak
Clients (users/API keys)  = 100 million
Rate limit rules          = ~500 distinct rules (endpoint × client tier combinations)
Storage per client        = ~100 bytes (counters + timestamps)
Total counter storage     = 100M × 100B = ~10 GB (fits comfortably in Redis)
Redis ops/sec             = 1M req/sec → 1M Redis calls/sec (1 INCR per request)
```

---

## 3. High-Level Architecture

```
 Client Request
      │
      ▼
 ┌──────────────┐
 │  API Gateway │  (or middleware in each service)
 │              │
 │  Rate Limiter│◀── Rule Config Service (dynamic rules)
 │  Middleware  │
 └──────┬───────┘
        │
  ┌─────▼──────────────────────┐
  │   Rate Limiter Service      │  (stateless, horizontally scaled)
  │                             │
  │  1. Identify client key     │
  │  2. Fetch applicable rules  │
  │  3. Check counter in Redis  │
  │  4. Allow / Deny            │
  └─────┬───────────────────────┘
        │
  ┌─────▼──────────────────┐
  │   Redis Cluster         │  (distributed counters — source of truth)
  │   (rate limit counters) │
  └─────────────────────────┘
        │
  ┌─────▼──────────────────┐
  │   Rule Config Store     │  (MySQL + local cache)
  │   (rules per endpoint   │
  │    per client tier)     │
  └─────────────────────────┘
        │
  ┌─────▼──────────────────┐
  │   Monitoring / Alerts   │  (Prometheus, throttle dashboards)
  └─────────────────────────┘
```

---

## 4. Rate Limiting Algorithms

This is the **core of the interview discussion**. Know all 4 algorithms, their trade-offs, and when to use each.

---

### 4.1 Token Bucket ⭐ (Most Common / Recommended)

```
Bucket holds max N tokens.
Tokens refill at rate R tokens/sec.
Each request consumes 1 token.
If bucket is empty → reject request (429).

         Refill: R tokens/sec
              │
              ▼
  ┌─────────────────────────┐
  │  ● ● ● ● ●  (5 tokens) │  ← bucket (capacity = 10)
  └─────────────────────────┘
       │
  Request arrives → remove 1 token → allow
  No tokens → reject
```

**Properties:**
- Allows **bursting** up to N requests at once (bucket fills up during idle time).
- Smooth average rate enforced.
- **Best for:** APIs where short bursts are acceptable (most common use case).

**Burst Capacity vs Refill Rate — Two Independent Knobs (Staff-Level):**
```
capacity     = max tokens the bucket can hold  →  controls burst ceiling
refill_rate  = tokens added per second         →  controls sustained throughput

Example: capacity=100, refill_rate=10/sec
  → Client idle for 10 sec → bucket full (100 tokens)
  → Client fires 100 requests in 1 sec → all allowed (burst)
  → Then only 10 req/sec sustained going forward

Tuning per use case:
  Login endpoint:   capacity=5,  refill=1/sec   → small burst, tight sustained
  Search endpoint:  capacity=50, refill=20/sec  → allows interaction bursts
  Payment API:      capacity=3,  refill=0.1/sec → extreme tightness

These are separate columns in rate_limit_rules table:
  burst_size  INT  → capacity
  refill_rate DECIMAL → tokens/sec
```

**Redis Implementation:**
```
Key:   ratelimit:{clientId}:{endpoint}
Fields: tokens (current count), last_refill (timestamp)

Algorithm (Lua script — atomic):
  local capacity    = tonumber(ARGV[1])  -- burst ceiling
  local refill_rate = tonumber(ARGV[2])  -- tokens/sec
  now = current_time_ms
  elapsed = now - last_refill
  tokens = min(capacity, tokens + (elapsed / 1000) × refill_rate)
  last_refill = now
  if tokens >= 1:
      tokens -= 1
      return ALLOW
  else:
      return DENY
```

---

### 4.2 Leaky Bucket

```
Requests enter a queue (bucket).
Queue drains at a fixed rate (leak rate).
If queue is full → reject (overflow).

  Requests ──▶ [ ● ● ● ● ● ] ──▶ process at fixed rate R
                  (queue)
```

**Properties:**
- Output rate is **always constant** — requests are smoothed.
- No burst allowed.
- **Best for:** Downstream systems that can't handle bursts (e.g., legacy APIs, payment gateways).
- **Downside:** Requests pile up in queue; added latency.

---

### 4.3 Fixed Window Counter

```
Divide time into fixed windows (e.g., each minute).
Count requests per client per window.
If count > limit → reject.

  Window 1 [00:00–01:00]: count = 98/100 → allow
  Window 2 [01:00–02:00]: count = 0/100 → allow
  
  Problem: Boundary burst:
  99 requests at 00:59 + 99 requests at 01:01 = 198 in 2 sec!
```

**Properties:**
- Simple Redis INCR + EXPIRE.
- **Boundary burst problem** — doubles effective rate at window boundaries.
- **Best for:** Simple use cases where boundary bursting is acceptable.

**Redis:**
```
Key:   ratelimit:{clientId}:{window_start}
cmd:   INCR key → count
       EXPIRE key 60 (if first request in window)
       if count > limit → DENY
```

---

### 4.4 Sliding Window Log

```
Store timestamp of every request in a sorted set.
On each request:
  1. Remove entries older than windowSize from ZSET
  2. Count remaining entries
  3. If count < limit → add current timestamp → ALLOW
  4. Else → DENY

Redis ZSET:
  Key:   ratelimit:{clientId}
  Score: timestamp (ms)
  Value: requestId
```

**Properties:**
- Most **accurate** — no boundary burst problem.
- High **memory usage** — stores every request timestamp.
- **Best for:** Low-volume, high-accuracy requirements (e.g., financial APIs).

---

### 4.5 Sliding Window Counter ⭐ (Best Balance)

Hybrid of Fixed Window + Sliding window. Approximates sliding window using two fixed windows.

```
Rate = prev_window_count × overlap_ratio + curr_window_count

Example (limit = 100/min):
  prev window [00:00–01:00]: 80 requests
  curr window [01:00–02:00]: 30 requests
  Current time: 01:15 (25% into curr window → 75% overlap with prev)

  Rate ≈ 80 × 0.75 + 30 = 90 → ALLOW (< 100)
```

**Properties:**
- Memory efficient (only 2 counters per client).
- Very accurate (< 1% error rate in practice).
- **Best for:** Most production distributed rate limiters.

---

## 5. Identifying the Client Key

```java
String buildRateLimitKey(HttpRequest request) {
    // Priority order:
    if (request.hasApiKey())    return "api:" + request.getApiKey();
    if (request.isAuthorized()) return "user:" + request.getUserId();
    return "ip:" + request.getClientIP();
}
```

**Key dimensions for rate limiting:**

| Dimension | Example Key | Use Case |
|---|---|---|
| Per User | `user:{userId}` | Authenticated API limits |
| Per API Key | `api:{apiKey}` | Developer/business tier limits |
| Per IP | `ip:{ipAddr}` | Unauthenticated / public endpoints |
| Per Endpoint | `user:{userId}:endpoint:{path}` | Endpoint-specific limits |
| Global | `global:{endpoint}` | Total traffic cap on an endpoint |

---

## 6. Rule Configuration Service

```sql
CREATE TABLE rate_limit_rules (
    rule_id       UUID PRIMARY KEY,
    client_tier   ENUM('FREE','PRO','ENTERPRISE','INTERNAL'),
    endpoint      VARCHAR(200),      -- NULL means applies to all
    limit_count   INT,
    window_sec    INT,
    algorithm     ENUM('TOKEN_BUCKET','SLIDING_WINDOW_COUNTER','FIXED_WINDOW'),
    burst_size    INT,               -- for token bucket
    is_active     BOOLEAN
);
```

**Rules cached locally** in each Rate Limiter Service instance (TTL: 60 sec). Changes propagate within 1 minute — acceptable for rule updates.

**Priority resolution (most specific wins):**
```
1. User + Endpoint specific rule
2. API Key + Endpoint rule
3. IP + Endpoint rule
4. Tier-level rule (FREE / PRO / ENTERPRISE)
5. Global default rule
```

**Multi-Level (Hierarchical) Rate Limiting — Hot Path Evaluation (Staff-Level):**
```
Problem: A single request may need to pass MULTIPLE rules simultaneously.
All applicable rules must be checked — the most restrictive one wins.

Example: User U123 (Enterprise tier) hits POST /payments
  Rule 1: user:U123   → 100 req/min   (per-user limit)
  Rule 2: enterprise  → 5000 req/min  (tier limit)
  Rule 3: /payments   → 500 req/min   (endpoint global cap)
  Rule 4: global      → 50K req/min   (system-wide cap)
  → ALL four counters are checked and incremented in a single Lua script
  → If ANY rule is exceeded → 429 (with which rule was breached in response)

Lua script for multi-rule evaluation:
  local results = {}
  for i, key in ipairs(KEYS) do
      local limit = tonumber(ARGV[i])
      local count = redis.call('INCR', key)
      if count == 1 then redis.call('EXPIRE', key, tonumber(ARGV[#ARGV])) end
      if count > limit then
          table.insert(results, key)  -- record which rule was breached
      end
  end
  if #results > 0 then return results end  -- DENY + which rule
  return {}                                 -- ALLOW

Response on breach:
  HTTP 429
  X-RateLimit-Violated-Rule: "endpoint:/payments:global"
  Retry-After: 47

Performance: all N rule counters evaluated in 1 Lua round trip to Redis.
             N is small (typically 3–5); no extra latency vs single-rule check.
```

---

## 7. Distributed Rate Limiting Design

**The challenge:** 10 Rate Limiter nodes each have their own memory. A client hitting different nodes could exceed the limit N× times.

**Solution: Redis as centralized counter store**

```
Node 1 ──▶ Redis INCR ──▶ global counter
Node 2 ──▶ Redis INCR ──▶ same counter
Node 3 ──▶ Redis INCR ──▶ same counter

All nodes share the same Redis counter → consistent global rate limiting
```

**Lua Script for atomicity (Sliding Window Counter):**
```lua
-- atomic: compare + increment in one Redis round trip
local key = KEYS[1]
local now = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local limit = tonumber(ARGV[3])

-- Remove old window entries
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- Count current window
local count = redis.call('ZCARD', key)

if count < limit then
    redis.call('ZADD', key, now, now)
    redis.call('EXPIRE', key, window / 1000)
    return 1  -- ALLOW
else
    return 0  -- DENY
end
```

**Why Lua script?** Atomic execution on Redis — no race condition between COUNT and INCR.

**Redis Cluster Shard Hotspot Problem (Staff-Level):**
```
Problem: A viral API key (e.g., a major enterprise client) generates 500K req/sec.
         All their keys hash to the same Redis slot → single shard becomes the bottleneck.

         Key: ratelimit:api:ACME_KEY:search
         Redis slot = CRC16("api:ACME_KEY:search") % 16384 → always slot 4291
         → All 500K req/sec hit shard owning slot 4291 → CPU saturation

Solution A — Hash Tag control (preferred):
  Force all keys for a client into the SAME slot intentionally (for multi-key Lua atomicity)
  but distribute ACROSS clients using a client_shard suffix:

  key = "ratelimit:{api:ACME_KEY:shard:3}:search"
  Redis hash tag = content inside {} = "api:ACME_KEY:shard:3"
  Shard suffix = hash(clientId) % NUM_SHARDS  (e.g., 0–7)
  → Each client consistently maps to 1 of 8 Redis shards → load spread

Solution B — Counter partitioning (for extreme traffic):
  Instead of 1 counter key, maintain N replica counter keys per client:
    ratelimit:api:ACME_KEY:search:p0  → shard A
    ratelimit:api:ACME_KEY:search:p1  → shard B
    ratelimit:api:ACME_KEY:search:p2  → shard C
  Each node picks partition = hash(node_id) % N  → writes to that partition
  Read path: SUM all N partitions to get real count
  Trade-off: read is more expensive (N Redis calls); use for write-heavy hot clients only

Decision:
  Default: Solution A (hash tag shard affinity) — zero read overhead
  Identified hot clients (>100K req/sec): Solution B (partitioned counters)
```

---

## 8. Data Flow — Request Processing

```
Client ──▶ GET /api/search

API Gateway / Middleware
        │
        ▼
  Build key: "user:U123:search"
        │
        ▼
  Fetch rule: 200 req/min (Sliding Window Counter)
  (from local cache → Redis config cache → MySQL)
        │
        ▼
  Execute Lua script on Redis:
    prev_count = HGET ratelimit:U123:search:prev_window
    curr_count = HGET ratelimit:U123:search:curr_window
    rate = prev × overlap + curr
        │
  ┌─────┴──────────────────────────┐
  │ rate < 200           rate ≥ 200│
  ▼                                ▼
ALLOW                        DENY (429)
  │                          Set Retry-After header:
INCR curr_count              retry_after = window_end - now
  │
Forward to upstream service
```

---

## 9. Response Headers

Always return informative rate limit headers:

```
HTTP/1.1 200 OK
X-RateLimit-Limit:     200
X-RateLimit-Remaining: 143
X-RateLimit-Reset:     1741201800   (unix timestamp of window reset)

HTTP/1.1 429 Too Many Requests
X-RateLimit-Limit:     200
X-RateLimit-Remaining: 0
X-RateLimit-Reset:     1741201860
Retry-After:           47           (seconds until retry)
```

---

## 10. Failure Handling

| Failure | Strategy |
|---|---|
| **Redis unreachable** | **Fail open** — allow all requests (rate limiter unavailable; don't block traffic) |
| **Redis timeout** | Short timeout (< 2ms); fail open if exceeded |
| **Redis partial failure (one node)** | Redis Cluster auto-routes to replica |
| **Rate Limiter Service crash** | Load balancer routes to healthy nodes |
| **Rule config DB down** | Serve stale rules from local cache |

> **Fail open vs Fail closed:**  
> For rate limiters — **fail open** is preferred. It's better to let some excess traffic through than to block all legitimate traffic when the rate limiter has issues.

---

## 11. Optimizations

### 11.1 Local Cache (Reduce Redis Calls)
For very high-traffic clients, maintain a **local approximate counter** in the service node memory:
- Sync with Redis every 100ms (batched INCR).
- Allows 10M+ req/sec without Redis bottleneck.
- Trade-off: slight over-allowance during sync interval.

**Overage Bound Analysis (Staff-Level):**
```
Max overage = nodes × rate_per_node × sync_interval

Example: 10 nodes, limit=1000 req/min, sync every 100ms
  Each node allows up to: 1000 × (100ms / 60000ms) ≈ 1.67 req per sync interval
  10 nodes × 1.67 = ~17 extra requests per 100ms window  →  ~1.7% overage
  → Acceptable if SLA says "<5% overage"; document this explicitly as product decision.

Node crash during sync interval:
  Unsynced local counts are LOST → Redis under-counts → slight under-enforcement
  Mitigation: flush local counter to Redis before graceful shutdown (SIGTERM handler)
  For ungraceful crash: accept loss; the overage is bounded by 1 sync interval's worth
  → At 100ms sync, max lost count per node ≈ rate × 0.1 sec → negligible

When NOT to use local cache:
  → Strict financial/security endpoints (payment, auth) — always go direct to Redis
  → Only apply to high-volume, tolerance-for-overage endpoints (search, listing)
```

### 11.2 Redis Pipeline / Batch
- Batch multiple client checks into a single Redis pipeline round trip.
- Reduces network overhead from N round trips to 1.

### 11.3 Sliding Window Counter vs Log
- Use **Sliding Window Counter** for most endpoints (2 counters, O(1) memory).
- Reserve **Sliding Window Log** only for low-volume, high-accuracy endpoints.

---

## 12. Redis Data Structure Summary

| Algorithm | Redis Structure | Memory Per Client |
|---|---|---|
| Token Bucket | HASH (tokens, last_refill) | ~50 bytes |
| Fixed Window | STRING (counter) | ~20 bytes |
| Sliding Window Log | ZSET (timestamps) | ~100B × req count |
| Sliding Window Counter | 2× STRING (prev + curr count) | ~40 bytes |

---

## 13. Key Design Decisions & Trade-offs

| Decision | Choice | Trade-off |
|---|---|---|
| **Algorithm** | Sliding Window Counter | Best accuracy/memory balance; < 1% error |
| **Storage** | Redis Cluster | Fast atomic ops; single source of truth |
| **Atomicity** | Lua scripts | No race conditions; single round trip |
| **Failure mode** | Fail open | Traffic not blocked; risk of slight over-limit |
| **Rule config** | Local cache (60s TTL) | Low latency; rules update within 1 min |
| **Client key** | API key > userId > IP | Most granular identification first |
| **Distributed sync** | Centralized Redis | Consistent limits; Redis is the bottleneck |

---

## 14. Where to Place the Rate Limiter

```
Option A: API Gateway (Centralized)
  ✅ Single enforcement point
  ✅ Language-agnostic
  ❌ Gateway becomes bottleneck; all services coupled to it

Option B: Middleware in each service (Distributed)
  ✅ No single bottleneck
  ✅ Service-specific rules
  ❌ Code duplication; harder to update consistently

Option C: Sidecar Proxy (Best for microservices)
  ✅ Decoupled from service code
  ✅ Independently deployable (like Envoy sidecar)
  ❌ Infrastructure complexity

Recommended: API Gateway for external traffic + Sidecar for inter-service limits.
```

---

## 15. Monitoring & Observability

| Signal | Metric / Alert |
|---|---|
| **Throttle rate** | % of requests returning 429; spike = abuse or misconfigured limit |
| **Redis latency** | P99 response time for INCR ops; alert if > 3ms |
| **Rule cache miss rate** | Frequency of fallback to DB for rule lookup |
| **Client blacklist requests** | Clients consistently at 100% throttle → candidate for blocking |
| **False positive rate** | Legitimate requests throttled — monitor via client complaints |

---

## 16. Thundering Herd on Window Reset

```
Problem:
  At T=60s (window reset), ALL throttled clients receive Retry-After: 0
  → They all retry simultaneously → 10,000 req spike in 1 sec → overwhelms upstream

Example:
  10,000 clients are throttled at 00:59.9
  Window resets at 01:00.0
  All 10,000 see Retry-After: 0 → instant thundering herd

Solution 1 — Jittered Retry-After:
  Instead of: Retry-After = window_end - now
  Return:     Retry-After = (window_end - now) + random(0, jitter_sec)

  jitter_sec = min(10, window_sec × 0.1)  → 10% of window size, max 10 sec
  Effect: retries spread across [T+0, T+10s] → 10× reduction in spike height

Solution 2 — Exponential Backoff guidance in response:
  Retry-After: 5
  X-RateLimit-Backoff-Multiplier: 2   ← hint to client to use exponential backoff
  Well-behaved clients back off exponentially; spike naturally flattens

Solution 3 — Token Bucket absorbs it naturally:
  With Token Bucket, window reset is continuous (tokens refill smoothly)
  No hard boundary → no thundering herd at all
  → Another reason Token Bucket is preferred for external-facing APIs

Recommendation:
  Fixed / Sliding Window endpoints → always add jitter to Retry-After
  Token Bucket endpoints → inherently immune, no jitter needed
```

---

## 17. Shadow Mode & Safe Rule Rollout (Staff-Level)

```
Problem: Rolling out a new rate limit rule (e.g., /login: 5 req/min) incorrectly
         can block all legitimate logins immediately → P0 incident.

Phase 1 — Shadow Mode (observe-only):
  Rate limiter evaluates rule but does NOT enforce it.
  Logs: { rule: "login:5/min", client: U123, would_have_throttled: true }
  Monitor for 24–48 hours:
    → How many legitimate users would have been throttled?
    → Is the limit too tight? Adjust before enforcement.
  Implementation: rule has flag enforce=false in rule config table
                  Lua script increments counter + checks limit but always returns ALLOW
                  Emits shadow_429 metric instead of real 429

Phase 2 — Gradual Enforcement (canary):
  Enforce on X% of traffic, observe real 429 rate:
    Day 1:  enforce on 1%  of clients → watch for complaints / error spike
    Day 3:  enforce on 10% → validate at scale
    Day 7:  enforce on 100% → full rollout
  Canary key: hash(clientId) % 100 < canary_percent → enforce else skip

Phase 3 — Headroom Start:
  Start limit at 10× intended value, reduce weekly:
    Week 1: limit = 500/min  (intended: 50/min)
    Week 2: limit = 200/min
    Week 3: limit = 100/min
    Week 4: limit = 50/min  ← target
  Gives clients time to adapt; no sudden disruption

Rollback:
  Rule config is in DB with enforce flag → instant rollback by flipping enforce=false
  Propagates to all nodes within 60 sec (local cache TTL)
  No deployment needed → safe and fast

Dashboard signals to watch during rollout:
  shadow_429_rate   → would-be throttle rate (Phase 1)
  real_429_rate     → actual throttle rate (Phase 2+)
  legitimate_retry_success_rate → are real users recovering after 429?
  client_complaint_tickets      → leading indicator of over-aggressive limiting
```

---

## 18. Future Enhancements
- **Adaptive Rate Limiting** — dynamically adjust limits based on server load (auto-throttle during high CPU).
- **Client Reputation Score** — penalize clients with history of abuse with lower limits.
- **Geolocation-based limits** — different limits per region (GDPR, regional traffic control).
- **Cost-based limiting** — limit by compute cost of requests, not just count (expensive ML endpoints throttled differently).

---

*Document prepared for SDE 3 system design interviews. Focus areas: rate limiting algorithms (Token Bucket vs Sliding Window), distributed counters with Redis, atomic Lua scripts, failure modes (fail open), and placement strategy.*
