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
 * Token Bucket rate limiting algorithm.
 *
 * <p><b>How it works (HLD §4.1):</b>
 * <ul>
 *   <li>A bucket holds up to {@code burstSize} tokens (max burst capacity).</li>
 *   <li>Tokens refill continuously at {@code refillRate} tokens/second.</li>
 *   <li>Each request consumes 1 token. Empty bucket → 429.</li>
 * </ul>
 *
 * <p><b>Redis structure:</b> HASH with fields {@code tokens} and {@code last_refill_ms}.
 *
 * <p><b>Atomicity:</b> The check + refill + decrement is executed in a single Lua script
 * to prevent race conditions between concurrent nodes. No separate WATCH/MULTI/EXEC needed.
 *
 * <p><b>Key benefit over Fixed/Sliding Window:</b> Tokens refill smoothly — no hard window
 * boundary means no thundering herd on reset (HLD §16).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenBucketAlgorithm implements RateLimitAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final Random jitterRandom = new Random();

    /**
     * Lua script (atomic):
     * 1. Load current tokens and last refill timestamp from Redis HASH.
     * 2. Calculate elapsed time since last refill.
     * 3. Refill tokens: min(capacity, currentTokens + elapsed * refillRate).
     * 4. If tokens >= 1: decrement, persist, return ALLOW with remaining count.
     * 5. Else: return DENY with time-until-next-token.
     *
     * <p>Returns a list: [allowed (1/0), tokens_remaining, reset_at_ms]
     */
    private static final RedisScript<List<Long>> TOKEN_BUCKET_SCRIPT = buildScript("""
            local key         = KEYS[1]
            local capacity    = tonumber(ARGV[1])
            local refill_rate = tonumber(ARGV[2])
            local now_ms      = tonumber(ARGV[3])
            local ttl_sec     = tonumber(ARGV[4])
            
            local data       = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
            local tokens     = tonumber(data[1]) or capacity
            local last_fill  = tonumber(data[2]) or now_ms
            
            -- Refill: add proportional tokens based on elapsed time
            local elapsed_sec = (now_ms - last_fill) / 1000.0
            local refilled    = tokens + (elapsed_sec * refill_rate)
            tokens = math.min(capacity, refilled)
            
            local allowed
            if tokens >= 1 then
                tokens   = tokens - 1
                allowed  = 1
            else
                allowed  = 0
            end
            
            -- Persist updated bucket state
            redis.call('HSET', key, 'tokens', tokens, 'last_refill_ms', now_ms)
            redis.call('EXPIRE', key, ttl_sec)
            
            -- Return: [allowed, floor(tokens), reset_at_ms]
            -- reset_at_ms = time until next token arrives (1/refill_rate seconds from now)
            local ms_until_token = math.ceil((1 - tokens) / refill_rate * 1000)
            return {allowed, math.floor(tokens), ms_until_token}
            """);

    @SuppressWarnings("unchecked")
    private static RedisScript<List<Long>> buildScript(String script) {
        DefaultRedisScript<List<Long>> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(script);
        redisScript.setResultType((Class<List<Long>>) (Class<?>) List.class);
        return redisScript;
    }

    /**
     * @param extraArgs [0] = burstSize (Integer), [1] = refillRate (Double)
     */
    @Override
    public RateLimitResult checkAndIncrement(String redisKey, int limitCount,
                                             int windowSec, Object... extraArgs) {
        int    burstSize   = (Integer) extraArgs[0];
        double refillRate  = (Double)  extraArgs[1];
        long   nowMs       = System.currentTimeMillis();
        // TTL: key lives for at least (burstSize / refillRate) seconds = time to refill from 0
        long   ttlSec      = Math.max(windowSec, (long) Math.ceil(burstSize / refillRate));

        List<Long> result = redisTemplate.execute(
                TOKEN_BUCKET_SCRIPT,
                List.of(redisKey),
                String.valueOf(burstSize),
                String.valueOf(refillRate),
                String.valueOf(nowMs),
                String.valueOf(ttlSec)
        );

        if (result == null || result.isEmpty()) {
            log.warn("Token bucket Lua script returned null for key={}", redisKey);
            return RateLimitResult.failOpen();
        }

        boolean allowed          = result.get(0) == 1L;
        long    tokensRemaining  = result.get(1);
        long    msUntilNextToken = result.get(2);

        long resetAtEpochSec = (nowMs + msUntilNextToken) / 1000;

        if (allowed) {
            return RateLimitResult.allow(burstSize, tokensRemaining, resetAtEpochSec);
        }

        // Add jitter to Retry-After to prevent thundering herd (HLD §16)
        long retryAfterSec = (msUntilNextToken / 1000) + jitterRandom.nextInt(3);
        return RateLimitResult.deny(burstSize, resetAtEpochSec, retryAfterSec,
                "token_bucket:" + redisKey);
    }
}
