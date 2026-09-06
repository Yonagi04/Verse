package com.yonagi.verse.service.pricing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.PricePeriodType;
import com.yonagi.verse.dao.entity.LlmPricingPeakPeriodDO;
import com.yonagi.verse.dao.entity.LlmServicePricingDO;
import com.yonagi.verse.dao.mapper.LlmPricingPeakPeriodMapper;
import com.yonagi.verse.dao.mapper.LlmServicePricingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class PricingResolver {
    public static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final LlmServicePricingMapper pricingMapper;
    private final LlmPricingPeakPeriodMapper peakMapper;
    private final StringRedisTemplate stringRedisTemplate;

    public PricingSnapshot resolve(Long tenantId, Long serviceId, Instant requestStartedAt) {
        String cacheKey = RedisKeyConstant.LLM_SERVICE_PRICING_KEY + serviceId;
        String cached = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                PricingSnapshot snapshot = JSON.parseObject(cached, PricingSnapshot.class);
                if (contains(snapshot, requestStartedAt)) return snapshot;
            } catch (Exception ignored) {
                stringRedisTemplate.delete(cacheKey);
            }
        }
        LocalDateTime local = LocalDateTime.ofInstant(requestStartedAt, SHANGHAI);
        LlmServicePricingDO pricing = pricingMapper.selectOne(Wrappers.lambdaQuery(LlmServicePricingDO.class)
                .eq(LlmServicePricingDO::getTenantId, tenantId)
                .eq(LlmServicePricingDO::getServiceId, serviceId)
                .le(LlmServicePricingDO::getEffectiveFrom, local)
                .and(q -> q.isNull(LlmServicePricingDO::getEffectiveTo)
                        .or().gt(LlmServicePricingDO::getEffectiveTo, local))
                .orderByDesc(LlmServicePricingDO::getEffectiveFrom)
                .last("LIMIT 1"));
        if (pricing == null) return PricingSnapshot.unpriced();
        List<LlmPricingPeakPeriodDO> periods = peakMapper.selectList(Wrappers.lambdaQuery(LlmPricingPeakPeriodDO.class)
                .eq(LlmPricingPeakPeriodDO::getPricingId, pricing.getPricingId()));
        int minute = local.getHour() * 60 + local.getMinute();
        int weekdayBit = 1 << (local.getDayOfWeek().getValue() - 1);
        LlmPricingPeakPeriodDO peak = periods.stream().filter(p -> (p.getWeekdayMask() & weekdayBit) != 0
                && p.getStartMinute() <= minute && minute < p.getEndMinute()).findFirst().orElse(null);
        PricingSnapshot snapshot = snapshot(pricing, peak);
        // 仅有基础价（无高峰期规则）时价格在生效区间内恒定，才可安全缓存；
        // 存在 peak 规则时价格随日内时间变化，缓存会串价，必须实时解析。
        if (pricing.getEffectiveTo() == null && periods.isEmpty()) {
            stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(snapshot), 30, TimeUnit.MINUTES);
        }
        return snapshot;
    }

    private PricingSnapshot snapshot(LlmServicePricingDO pricing, LlmPricingPeakPeriodDO peak) {
        BillingMode mode = BillingMode.valueOf(pricing.getBillingMode());
        BigDecimal miss = peak == null ? pricing.getBaseCacheMissInputPriceFen() : peak.getPeakCacheMissInputPriceFen();
        BigDecimal hit = peak == null ? pricing.getBaseCacheHitInputPriceFen() : peak.getPeakCacheHitInputPriceFen();
        BigDecimal output = peak == null ? pricing.getBaseOutputPriceFen() : peak.getPeakOutputPriceFen();
        BigDecimal request = peak == null ? pricing.getBaseRequestPriceFen() : peak.getPeakRequestPriceFen();
        if (mode == BillingMode.TOKEN && hit == null) hit = miss;
        return new PricingSnapshot(pricing.getPricingId(), mode, pricing.getCurrency(),
                peak == null ? PricePeriodType.BASE : PricePeriodType.PEAK,
                peak == null ? null : peak.getPeriodId(), miss, hit, output, request,
                pricing.getEffectiveFrom().atZone(SHANGHAI).toInstant(),
                pricing.getEffectiveTo() == null ? null : pricing.getEffectiveTo().atZone(SHANGHAI).toInstant());
    }

    public void invalidateCurrent(Long serviceId) {
        stringRedisTemplate.delete(RedisKeyConstant.LLM_SERVICE_PRICING_KEY + serviceId);
    }

    private boolean contains(PricingSnapshot snapshot, Instant instant) {
        return snapshot != null && snapshot.priced() && snapshot.effectiveFrom() != null
                && !instant.isBefore(snapshot.effectiveFrom())
                && (snapshot.effectiveTo() == null || instant.isBefore(snapshot.effectiveTo()));
    }
}
