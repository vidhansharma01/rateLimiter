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
import java.util.UUID;

/**
 * Sliding Window Log rate limiting algorithm (HLD §4.4).
 *
 * <p><b>How it works:</b>
 * Stores the timestamp of every request in a Redis Sorted Set (ZSET).
 * On each request:
 * <ol>
 *   <li>Remove entries older than {@code windowSec} from the ZSET (ZREMRANGEBYSCORE).</li>
 *   <li>Count remaining entries (ZCARD) → requests in current sliding window.</li>
 *   <li>If count &lt; limit → add current timestamp → ALLOW.</li>
 *   <li>Else → DENY.</li>
 * </ol>
 *
 * <p><b>Properties:</b>
 * <ul>
 *   <li>Most accurate — no boundary burst problem.</li>
 *   <li>High memory: ~100 bytes per request entry in ZSET.</li>
 *   <li>Best for: low-volume, high-accuracy endpoints (financial APIs, auth).</li>
 * </ul>
 *
 * <p><b>Redis structure:</b> ZSET — score = timestamp_ms, value = unique requestId.
 * Unique value prevents score collisions for requests arriving in the same millisecond.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlidingWindowLogAlgorithm implements RateLimitAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final Random jitterRandom = new Random();

    /**
     * Lua script — atomic:
     * 1. Remove stale entries outside the sliding window.
     * 2. Count current entries.
     * 3. Allow + insert OR deny.
     *
     * <p>Returns: [allowed (1/0), current_count]
     */
    private static final RedisScript<List<Long>> SLIDING_LOG_SCRIPT = buildScript("""
            local key        = KEYS[1]
            local now_ms     = tonumber(ARGV[1])
            local window_ms  = tonumber(ARGV[2])
            local limit      = tonumber(ARGV[3])
            local request_id = ARGV[4]
            local ttl_sec    = tonumber(ARGV[5])
            
            -- Remove requests outside the sliding window
            redis.call('ZREMRANGEBYSCORE', key, 0, now_ms - window_ms)
            
            -- Count requests currently in window
            local count = redis.call('ZCARD', key)
            
            local allowed
            if count < limit then
                -- Add this request's timestamp (score=now_ms, value=unique requestId)
                redis.call('ZADD', key, now_ms, request_id)
                redis.call('EXPIRE', key, ttl_sec)
                allowed = 1
                count   = count + 1
            else
                allowed = 0
            end
            
            return {allowed, count}
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
        long nowMs    = System.currentTimeMillis();
        long windowMs = (long) windowSec * 1000;

        // Unique member value avoids ZADD score collision for same-millisecond requests
        String requestId = UUID.randomUUID().toString();

        List<Long> result = redisTemplate.execute(
                SLIDING_LOG_SCRIPT,
                List.of(redisKey),
                String.valueOf(nowMs),
                String.valueOf(windowMs),
                String.valueOf(limitCount),
                requestId,
                String.valueOf(windowSec + 1) // TTL slightly longer than window
        );

        if (result == null || result.isEmpty()) {
            log.warn("SlidingWindowLog Lua script returned null for key={}", redisKey);
            return RateLimitResult.failOpen();
        }

        boolean allowed      = result.get(0) == 1L;
        long    currentCount = result.get(1);
        long    resetAtMs    = nowMs + windowMs;
        long    resetEpoch   = resetAtMs / 1000;
        long    remaining    = Math.max(0, limitCount - currentCount);

        if (allowed) {
            return RateLimitResult.allow(limitCount, remaining, resetEpoch);
        }

        int  jitterMs      = jitterRandom.nextInt((int) Math.min(10_000, windowMs / 10));
        long retryAfterSec = (windowMs + jitterMs) / 1000;

        return RateLimitResult.deny(limitCount, resetEpoch, retryAfterSec,
                "sliding_window_log:" + redisKey);
    }
}
