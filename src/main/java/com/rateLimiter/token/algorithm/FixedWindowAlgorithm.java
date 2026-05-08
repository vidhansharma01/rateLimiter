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
 * Fixed Window Counter rate limiting algorithm (HLD §4.3).
 *
 * <p><b>How it works:</b>
 * <ul>
 *   <li>Time is divided into fixed windows (e.g., each minute).</li>
 *   <li>A counter per client per window is atomically incremented.</li>
 *   <li>If counter &gt; limit → reject. Otherwise → allow.</li>
 * </ul>
 *
 * <p><b>Known limitation:</b> Boundary burst problem — a client can fire
 * 2× the limit straddling a window boundary (e.g., 99 at 00:59, 99 at 01:01).
 * Use {@link SlidingWindowCounterAlgorithm} when boundary bursting is unacceptable.
 *
 * <p><b>Redis structure:</b> STRING key per window per client.
 * <br>Key: {@code {redisKey}:{window_start_epoch_sec}}
 * <br>Commands: INCR + EXPIRE (set only on first request in window).
 *
 * <p><b>Best for:</b> Simple use cases where slight boundary over-allowance is acceptable.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FixedWindowAlgorithm implements RateLimitAlgorithm {

    private final StringRedisTemplate redisTemplate;
    private final Random jitterRandom = new Random();

    /**
     * Lua script — atomic INCR + conditional EXPIRE.
     * Setting EXPIRE only when count == 1 ensures we don't reset the TTL on
     * subsequent requests, which would extend the window unintentionally.
     *
     * <p>Returns: [current_count, window_start_sec]
     */
    private static final RedisScript<List<Long>> FIXED_WINDOW_SCRIPT = buildScript("""
            local key        = KEYS[1]
            local limit      = tonumber(ARGV[1])
            local window_sec = tonumber(ARGV[2])
            
            local count = redis.call('INCR', key)
            if count == 1 then
                redis.call('EXPIRE', key, window_sec)
            end
            return {count}
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
        long nowSec           = System.currentTimeMillis() / 1000;
        long windowStartSec   = (nowSec / windowSec) * windowSec;
        long windowEndSec     = windowStartSec + windowSec;
        String windowedKey    = redisKey + ":" + windowStartSec;

        List<Long> result = redisTemplate.execute(
                FIXED_WINDOW_SCRIPT,
                List.of(windowedKey),
                String.valueOf(limitCount),
                String.valueOf(windowSec)
        );

        if (result == null || result.isEmpty()) {
            log.warn("FixedWindow Lua script returned null for key={}", redisKey);
            return RateLimitResult.failOpen();
        }

        long count     = result.get(0);
        long remaining = Math.max(0, limitCount - count);

        if (count <= limitCount) {
            return RateLimitResult.allow(limitCount, remaining, windowEndSec);
        }

        int  jitterSec     = jitterRandom.nextInt((int) Math.min(10, windowSec / 10 + 1));
        long retryAfterSec = (windowEndSec - nowSec) + jitterSec;

        return RateLimitResult.deny(limitCount, windowEndSec, retryAfterSec,
                "fixed_window:" + redisKey);
    }
}
