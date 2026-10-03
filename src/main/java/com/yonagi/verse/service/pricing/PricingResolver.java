package com.yonagi.verse.service.pricing;

import com.yonagi.verse.common.cache.QueryCacheTtl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.PricePeriodType;
import com.yonagi.verse.dao.entity.LlmPricingPeakPeriodDO;
import com.yonagi.verse.dao.entity.LlmServicePricingDO;
import com.yonagi.verse.dao.mapper.LlmPricingPeakPeriodMapper;
import com.yonagi.verse.dao.mapper.LlmServicePricingMapper;
import lombok.RequiredArgsConstructor;
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
    private final com.yonagi.verse.common.cache.QueryCache queryCache;

    public record Rules(
            /** 所有历史价格版本。 */ List<LlmServicePricingDO> prices,
            /** 价格版本对应的高峰期规则。 */ java.util.Map<Long, List<LlmPricingPeakPeriodDO>> peaks) { }

    public PricingSnapshot resolve(Long tenantId, Long serviceId, Instant requestStartedAt) {
        // 缓存规则而非最终价格，高峰时段和历史请求仍按各自发生时间解析。
        Rules rules = queryCache.read("pricing-rules", RedisKeyConstant.LLM_SERVICE_PRICING_RULES_KEY, List.of(tenantId, serviceId), Rules.class,
                List.of("t_llm_service_pricing", "t_llm_pricing_peak_period"), TimeUnit.SECONDS.toMillis(QueryCacheTtl.HOURS_4),
                () -> loadRules(tenantId, serviceId));
        LocalDateTime local = LocalDateTime.ofInstant(requestStartedAt, SHANGHAI);
        LlmServicePricingDO pricing = rules.prices().stream().filter(p -> !local.isBefore(p.getEffectiveFrom())
                && (p.getEffectiveTo() == null || local.isBefore(p.getEffectiveTo()))).findFirst().orElse(null);
        if (pricing == null) return PricingSnapshot.unpriced();
        List<LlmPricingPeakPeriodDO> periods = rules.peaks().getOrDefault(pricing.getPricingId(), List.of());
        int minute = local.getHour() * 60 + local.getMinute();
        int weekdayBit = 1 << (local.getDayOfWeek().getValue() - 1);
        LlmPricingPeakPeriodDO peak = periods.stream().filter(p -> (p.getWeekdayMask() & weekdayBit) != 0
                && p.getStartMinute() <= minute && minute < p.getEndMinute()).findFirst().orElse(null);
        return snapshot(pricing, peak);
    }

    private Rules loadRules(Long tenantId, Long serviceId) {
        List<LlmServicePricingDO> prices = pricingMapper.selectList(Wrappers.lambdaQuery(LlmServicePricingDO.class)
                .eq(LlmServicePricingDO::getTenantId, tenantId).eq(LlmServicePricingDO::getServiceId, serviceId)
                .orderByDesc(LlmServicePricingDO::getEffectiveFrom));
        java.util.Map<Long, List<LlmPricingPeakPeriodDO>> peaks = prices.isEmpty() ? java.util.Map.of() :
                peakMapper.selectList(Wrappers.lambdaQuery(LlmPricingPeakPeriodDO.class)
                        .in(LlmPricingPeakPeriodDO::getPricingId, prices.stream().map(LlmServicePricingDO::getPricingId).toList()))
                        .stream().collect(java.util.stream.Collectors.groupingBy(LlmPricingPeakPeriodDO::getPricingId));
        return new Rules(prices, peaks);
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
        // 统一写拦截器按价格表依赖失效，保留入口兼容现有提交后回调。
    }

}
