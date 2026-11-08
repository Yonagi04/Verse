package com.yonagi.verse.service.pricing;

import com.yonagi.verse.common.cache.QueryCache;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.PricePeriodType;
import com.yonagi.verse.dao.entity.LlmPricingPeakPeriodDO;
import com.yonagi.verse.dao.entity.LlmServicePricingDO;
import com.yonagi.verse.dao.mapper.LlmPricingPeakPeriodMapper;
import com.yonagi.verse.dao.mapper.LlmServicePricingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PricingResolverTest {
    private static final long TENANT_ID = 20L;
    private static final long SERVICE_ID = 30L;
    private static final LocalDateTime SWITCH_TIME = LocalDateTime.parse("2026-11-02T00:00:00");
    private final LlmServicePricingMapper prices = mock(LlmServicePricingMapper.class);
    private final LlmPricingPeakPeriodMapper peaks = mock(LlmPricingPeakPeriodMapper.class);
    private final QueryCache cache = mock(QueryCache.class);
    private final PricingResolver resolver = new PricingResolver(prices, peaks, cache);

    @BeforeEach
    void useRulesLoadedByService() {
        when(cache.read(eq("pricing-rules"), anyString(), eq(List.of(TENANT_ID, SERVICE_ID)),
                eq(PricingResolver.Rules.class), anyList(), anyLong(), any()))
                .thenAnswer(call -> call.<Supplier<PricingResolver.Rules>>getArgument(6).get());
    }

    @ParameterizedTest
    @CsvSource({
            "2026-11-01T15:59:59Z,1,BASE,10,4,20",
            "2026-11-01T16:00:00Z,2,BASE,30,30,40",
            "2026-11-02T00:59:59Z,2,BASE,30,30,40",
            "2026-11-02T01:00:00Z,2,PEAK,60,15,80",
            "2026-11-02T01:59:59Z,2,PEAK,60,15,80",
            "2026-11-02T02:00:00Z,2,BASE,30,30,40",
            "2026-11-03T01:00:00Z,2,BASE,30,30,40"
    })
    void usesRequestTimeForVersionAndShanghaiPeakBoundaries(String startedAt, long pricingId, PricePeriodType period,
                                                            BigDecimal miss, BigDecimal hit, BigDecimal output) {
        LlmServicePricingDO current = tokenPrice(2L, SWITCH_TIME, null, "30", null, "40");
        LlmServicePricingDO previous = tokenPrice(1L, SWITCH_TIME.minusDays(1), SWITCH_TIME, "10", "4", "20");
        when(prices.selectList(any())).thenReturn(List.of(current, previous));
        when(peaks.selectList(any())).thenReturn(List.of(mondayPeak()));

        PricingSnapshot result = resolver.resolve(TENANT_ID, SERVICE_ID, Instant.parse(startedAt));

        assertEquals(pricingId, result.pricingId());
        assertEquals(BillingMode.TOKEN, result.billingMode());
        assertEquals(period, result.periodType());
        assertEquals(period == PricePeriodType.PEAK ? 5L : null, result.periodId());
        assertEquals(miss, result.cacheMissInputPriceFen());
        assertEquals(hit, result.cacheHitInputPriceFen());
        assertEquals(output, result.outputPriceFen());
        assertEquals(pricingId == 1L ? Instant.parse("2026-11-01T16:00:00Z") : null, result.effectiveTo());
    }

    @Test
    void expiredVersionDoesNotPriceARequestInAConfigurationGap() {
        when(prices.selectList(any())).thenReturn(List.of(
                tokenPrice(2L, SWITCH_TIME.plusDays(1), null, "30", null, "40"),
                tokenPrice(1L, SWITCH_TIME.minusDays(1), SWITCH_TIME, "10", "4", "20")));
        when(peaks.selectList(any())).thenReturn(List.of());

        PricingSnapshot result = resolver.resolve(TENANT_ID, SERVICE_ID, Instant.parse("2026-11-01T16:00:00Z"));

        assertFalse(result.priced());
        assertNull(result.requestPriceFen());
    }

    @Test
    void noConfiguredVersionsProducesAnUnpricedSnapshotWithoutLookingUpPeakRules() {
        when(prices.selectList(any())).thenReturn(List.of());

        PricingSnapshot result = resolver.resolve(TENANT_ID, SERVICE_ID, Instant.parse("2026-11-01T16:00:00Z"));

        assertFalse(result.priced());
        assertNull(result.effectiveFrom());
        verifyNoInteractions(peaks);
    }

    @Test
    void requestModeUsesThePeakRequestPriceWithoutSynthesizingTokenRates() {
        LlmServicePricingDO price = new LlmServicePricingDO();
        price.setPricingId(2L);
        price.setBillingMode(BillingMode.REQUEST.name());
        price.setCurrency("CNY");
        price.setEffectiveFrom(SWITCH_TIME);
        price.setBaseRequestPriceFen(new BigDecimal("2"));
        LlmPricingPeakPeriodDO peak = mondayPeak();
        peak.setPeakCacheMissInputPriceFen(null);
        peak.setPeakCacheHitInputPriceFen(null);
        peak.setPeakOutputPriceFen(null);
        peak.setPeakRequestPriceFen(new BigDecimal("3.5"));
        when(prices.selectList(any())).thenReturn(List.of(price));
        when(peaks.selectList(any())).thenReturn(List.of(peak));

        PricingSnapshot peakSnapshot = resolver.resolve(TENANT_ID, SERVICE_ID, Instant.parse("2026-11-02T01:00:00Z"));
        PricingSnapshot baseSnapshot = resolver.resolve(TENANT_ID, SERVICE_ID, Instant.parse("2026-11-02T02:00:00Z"));

        assertEquals(BillingMode.REQUEST, peakSnapshot.billingMode());
        assertEquals(new BigDecimal("3.5"), peakSnapshot.requestPriceFen());
        assertEquals(new BigDecimal("2"), baseSnapshot.requestPriceFen());
        assertNull(peakSnapshot.cacheHitInputPriceFen());
    }

    private static LlmServicePricingDO tokenPrice(long id, LocalDateTime from, LocalDateTime to,
                                                   String miss, String hit, String output) {
        LlmServicePricingDO price = new LlmServicePricingDO();
        price.setPricingId(id);
        price.setBillingMode(BillingMode.TOKEN.name());
        price.setCurrency("CNY");
        price.setEffectiveFrom(from);
        price.setEffectiveTo(to);
        price.setBaseCacheMissInputPriceFen(new BigDecimal(miss));
        price.setBaseCacheHitInputPriceFen(hit == null ? null : new BigDecimal(hit));
        price.setBaseOutputPriceFen(new BigDecimal(output));
        return price;
    }

    private static LlmPricingPeakPeriodDO mondayPeak() {
        LlmPricingPeakPeriodDO peak = new LlmPricingPeakPeriodDO();
        peak.setPricingId(2L);
        peak.setPeriodId(5L);
        peak.setWeekdayMask(1);
        peak.setStartMinute(9 * 60);
        peak.setEndMinute(10 * 60);
        peak.setPeakCacheMissInputPriceFen(new BigDecimal("60"));
        peak.setPeakCacheHitInputPriceFen(new BigDecimal("15"));
        peak.setPeakOutputPriceFen(new BigDecimal("80"));
        return peak;
    }
}
