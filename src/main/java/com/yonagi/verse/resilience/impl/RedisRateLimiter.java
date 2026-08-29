package com.yonagi.verse.resilience.impl;

import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.resilience.api.RateLimitContext;
import com.yonagi.verse.resilience.api.RateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 限流实现 — RPM 硬限流用 Redisson {@link RRateLimiter}（请求前精确拦截），
 * TPM 软限流用 Redis 计数器（请求前读、请求后结算），三级维度（租户 → Key → 模型）。
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRateLimiter implements RateLimiter {

    private static final String DIM_TENANT = "tenant";
    private static final String DIM_KEY = "key";
    private static final String DIM_MODEL = "model";

    /**
     * TPM 计数窗口 TTL（秒）：epoch 分钟粒度下，2 分钟足够覆盖跨分钟边界
     */
    private static final int TPM_WINDOW_TTL_SECONDS = 120;

    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void check(RateLimitContext ctx) {
        checkDimension(DIM_TENANT, ctx.getTenantId(), ctx.getTenantRpm(), ctx.getTenantTpm());
        checkDimension(DIM_KEY, ctx.getApiKeyId(), ctx.getApiKeyRpm(), ctx.getApiKeyTpm());
        checkDimension(DIM_MODEL, ctx.getServiceId(), ctx.getModelRpm(), ctx.getModelTpm());
    }

    @Override
    public void settle(RateLimitContext ctx, int totalTokens) {
        settleDimension(DIM_TENANT, ctx.getTenantId(), ctx.getTenantTpm(), totalTokens);
        settleDimension(DIM_KEY, ctx.getApiKeyId(), ctx.getApiKeyTpm(), totalTokens);
        settleDimension(DIM_MODEL, ctx.getServiceId(), ctx.getModelTpm(), totalTokens);
    }

    /**
     * 单维度检查：RPM 硬限流（请求前扣减）+ TPM 软限流（读已结算计数），任一超限抛 A000802。
     */
    private void checkDimension(String dimension, Long id, Integer rpm, Integer tpm) {
        if (id == null) {
            return;
        }
        if (rpm != null && rpm > 0 && !tryAcquireRpm(dimension, id, rpm)) {
            throw new ClientException(LlmForwardErrorCodeEnum.RATE_LIMIT_EXCEEDED);
        }
        if (tpm != null && tpm > 0 && currentTpm(dimension, id) >= tpm) {
            throw new ClientException(LlmForwardErrorCodeEnum.RATE_LIMIT_EXCEEDED);
        }
    }

    private boolean tryAcquireRpm(String dimension, Long id, int rpm) {
        RRateLimiter rateLimiter = redissonClient.getRateLimiter(
                RedisKeyConstant.RATE_LIMIT_RPM_KEY + dimension + ":" + id);
        rateLimiter.trySetRate(RateType.OVERALL, rpm, 1, RateIntervalUnit.MINUTES);
        return rateLimiter.tryAcquire();
    }

    private long currentTpm(String dimension, Long id) {
        String key = RedisKeyConstant.RATE_LIMIT_TPM_KEY + dimension + ":" + id + ":" + epochMinute();
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.warn("[llm-ratelimit] TPM 计数非数值: key={}, value={}", key, value);
            return 0;
        }
    }

    private void settleDimension(String dimension, Long id, Integer tpm, int totalTokens) {
        if (id == null || tpm == null || tpm <= 0 || totalTokens <= 0) {
            return;
        }
        String key = RedisKeyConstant.RATE_LIMIT_TPM_KEY + dimension + ":" + id + ":" + epochMinute();
        stringRedisTemplate.opsForValue().increment(key, totalTokens);
        stringRedisTemplate.expire(key, TPM_WINDOW_TTL_SECONDS, TimeUnit.SECONDS);
    }

    private long epochMinute() {
        return System.currentTimeMillis() / 60000;
    }
}
