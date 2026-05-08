package com.rateLimiter.token.algorithm;

import com.rateLimiter.token.core.RateLimitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * Sliding Window Counter rate limiting algorithm — the recommended default (HLD §4.5 ⭐).
 *
 * <p><b>How it works:</b>
 * Uses two adjacent fixed-window counters (previous and current) and computes an
 * approximate sliding window rate as a weighted combination:
 * <pre>
 *   rate ≈ prev_count × overlap_ratio + curr_count
 * </pre>
 * where {@code overlap_ratio} = fraction of the previous window still within the
 * current sliding window.
 *
 * <p><b>Example (HLD §4.5):</b>
 * <pre>
 *   prev window [00:00–01:00]: 80 requests
 *   curr window [01:00–02:00]: 30 requests
 *   Current time: 01:15 (25% into curr window → 75% overlap with prev)
 *   rate ≈ 80 × 0.75 + 30 = 90 → ALLOW (limit = 100)
 * </pre>
 *
 * <p><b>Properties:</b>
 * <ul>
 *   <li>Memory: 2 STRING counters (~40 bytes per client) — very efficient.</li>
 *   <li>Accuracy: &lt; 1% error rate in practice.</li>
 *   <li>Best for: most production distributed rate limiters.</li>
 * </ul>
 *
 * <p><b>Redis keys:</b>
 * <ul>
 *   <li>Current window: {@code {redisKey}:curr:{window_start}}</li>
 *   <li>Previous window: {@code {redisKey}:prev:{prev_window_start}}</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWindowCounterAlgorithm implements RateLimitAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final Random jitterRandom = new Random();

    /**
     * Lua script — atomic read of both window counters, sliding rate calculation,
     * and conditional increment. Single Redis round trip.
     *
     * <p>Returns: [allowed (1/0), curr_count, prev_count, curr_window_start_ms]
     */
    private static final RedisScript<List<Long>> SLIDING_WINDOW_COUNTER_SCRIPT =
            buildScript("""
                    local curr_key   = KEYS[1]
                    local prev_key   = KEYS[2]
                    local limit      = tonumber(ARGV[1])
                    local window_ms  = tonumber(ARGV[2])
                    local now_ms     = tonumber(ARGV[3])
                    
                    -- Determine position within current window (0.0 to 1.0)
                    local curr_window_start = math.floor(now_ms / window_ms) * window_ms
                    local elapsed_in_window = now_ms - curr_window_start
                    local overlap_ratio     = 1.0 - (elapsed_in_window / window_ms)
                    
                    local curr_count = tonumber(redis.call('GET', curr_key)) or 0
                    local prev_count = tonumber(redis.call('GET', prev_key)) or 0
                    
                    -- Weighted approximation of requests in the sliding window
                    local sliding_rate = (prev_count * overlap_ratio) + curr_count
                    
                    local allowed
                    if sliding_rate < limit then
                        redis.call('INCR', curr_key)
                        redis.call('EXPIRE', curr_key, math.ceil(window_ms / 1000) * 2)
                        allowed = 1
                        curr_count = curr_count + 1
                    else
                        allowed = 0
                    end
                    
                    return {allowed, curr_count, prev_count, curr_window_start}
                    """);

    @SuppressWarnings("unchecked")
    private static RedisScript<List<Long>> buildScript(String script) {
        DefaultRedisScript<List<Long>> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType((Class<List<Long>>) (Class<?>) List.class);
        return redisScript;
    }

    @Override
    public RateLimitResult checkAndIncrement(String redisKey, int limitCount,
                                             int windowSec, Object... extraArgs) {
        long nowMs     = System.currentTimeMillis();
        long windowMs  = (long) windowSec * 1000;

        long currWindowStart = (nowMs / windowMs) * windowMs;
        long prevWindowStart = currWindowStart - windowMs;

        String currKey = redisKey + ":curr:" + currWindowStart;
        String prevKey = redisKey + ":prev:" + prevWindowStart;

        List<Long> result = redisTemplate.execute(
                SLIDING_WINDOW_COUNTER_SCRIPT,
                List.of(currKey, prevKey),
                String.valueOf(limitCount),
                String.valueOf(windowMs),
                String.valueOf(nowMs)
        );

        if (result == null || result.isEmpty()) {
            log.warn("SlidingWindowCounter Lua script returned null for key={}", redisKey);
            return RateLimitResult.failOpen();
        }

        boolean allowed   = result.get(0) == 1L;
        long    currCount = result.get(1);
        long    resetAtMs = currWindowStart + windowMs;
        long    resetAtEpochSec = resetAtMs / 1000;
        long    remaining = Math.max(0, limitCount - currCount);

        if (allowed) {
            return RateLimitResult.allow(limitCount, remaining, resetAtEpochSec);
        }

        // Jittered Retry-After: spread retries across 10% of window (max 10 sec)
        long baseRetryMs = resetAtMs - nowMs;
        int  jitterMs    = jitterRandom.nextInt((int) Math.min(10_000, windowMs / 10));
        long retryAfterSec = (baseRetryMs + jitterMs) / 1000;

        return RateLimitResult.deny(limitCount, resetAtEpochSec, retryAfterSec,
                "sliding_window_counter:" + redisKey);
    }
}
