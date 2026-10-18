package com.yonagi.verse.service.usage.user;

import com.yonagi.verse.dao.entity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class UserModelUsageContributionPolicyTest {
    @Test void successfulFreeAndUnknownCostsStillCountWhileFailedCalculatedCostsDoNotCountActivity() {
        var u=new TokenUsageDO(); u.setStatus("SUCCESS"); var c=new TokenUsageCostDO(); c.setCostStatus("CALCULATED"); c.setCurrency("CNY"); c.setEstimatedCostFen(BigDecimal.ZERO);
        assertEquals(1,UserModelUsageContributionPolicy.contribution(u,c).successes());
        assertEquals(BigDecimal.ZERO,UserModelUsageContributionPolicy.contribution(u,c).cost());
        assertEquals(1,UserModelUsageContributionPolicy.contribution(u,null).unknown());
        c.setCostStatus("UNPRICED"); c.setEstimatedCostFen(null);
        assertEquals(1,UserModelUsageContributionPolicy.contribution(u,c).successes()); assertEquals(1,UserModelUsageContributionPolicy.contribution(u,c).unknown());
        u.setStatus("FAIL"); c.setCostStatus("CALCULATED"); c.setEstimatedCostFen(new BigDecimal("0.123456789012345678"));
        var result=UserModelUsageContributionPolicy.contribution(u,c); assertEquals(0,result.successes()); assertEquals(c.getEstimatedCostFen(),result.cost());
        c.setCostStatus("NOT_CHARGEABLE"); c.setEstimatedCostFen(null); assertEquals(0,UserModelUsageContributionPolicy.contribution(u,c).unknown());
    }
    @ParameterizedTest @ValueSource(strings={"-0.01","100000000000000000000","0.0000000000000000001"})
    void invalidAmountsNeverBecomeValidZero(String amount) {
        var u=new TokenUsageDO(); var c=new TokenUsageCostDO(); c.setCostStatus("CALCULATED"); c.setCurrency("CNY"); c.setEstimatedCostFen(new BigDecimal(amount));
        assertThrows(IllegalArgumentException.class,()->UserModelUsageContributionPolicy.contribution(u,c));
    }
    @Test void nonCnyAndContradictoryCostStateAreRejected() {
        var c=new TokenUsageCostDO(); c.setCostStatus("CALCULATED"); c.setCurrency("USD"); c.setEstimatedCostFen(BigDecimal.ONE);
        assertThrows(IllegalArgumentException.class,()->UserModelUsageContributionPolicy.contribution(new TokenUsageDO(),c));
        c.setCostStatus("UNCALCULABLE"); assertThrows(IllegalArgumentException.class,()->UserModelUsageContributionPolicy.contribution(new TokenUsageDO(),c));
    }
    @Test void accumulatedCostRejectsOverflow() {
        assertThrows(IllegalArgumentException.class,()->UserModelUsageContributionPolicy.checkTotal(new BigDecimal("100000000000000000000")));
    }
    @Test void duplicateHistoryMustMatchEveryOwnershipAndOutcomeDimension() {
        var a=new TokenUsageDO(); a.setUserId(1L); a.setTenantId(2L); a.setSource("API_KEY"); a.setRequestId("r"); a.setStatus("SUCCESS");
        a.setServiceId(3L); a.setOperation("CHAT_COMPLETIONS"); a.setRequestStartedAt(java.time.LocalDateTime.of(2026,10,18,12,0));
        var c=new TokenUsageCostDO(); c.setCostStatus("CALCULATED"); c.setCurrency("CNY"); c.setEstimatedCostFen(BigDecimal.ONE);
        var b=new TokenUsageDO(); var d=new TokenUsageCostDO(); org.springframework.beans.BeanUtils.copyProperties(a,b); org.springframework.beans.BeanUtils.copyProperties(c,d);
        assertTrue(UserModelUsageContributionPolicy.equivalent(a,c,b,d));
        java.util.List<java.util.function.Consumer<TokenUsageDO>> changes=java.util.List.of(x->x.setUserId(9L),x->x.setTenantId(9L),x->x.setSource("PLAYGROUND"),
                x->x.setRequestId("other"),x->x.setStatus("FAIL"),x->x.setServiceId(9L),x->x.setOperation("EMBEDDINGS"),x->x.setRequestStartedAt(a.getRequestStartedAt().plusDays(1)));
        for(var change:changes) { org.springframework.beans.BeanUtils.copyProperties(a,b); change.accept(b); assertFalse(UserModelUsageContributionPolicy.equivalent(a,c,b,d)); }
        org.springframework.beans.BeanUtils.copyProperties(a,b);
        assertFalse(UserModelUsageContributionPolicy.equivalent(a,null,b,d)); assertFalse(UserModelUsageContributionPolicy.equivalent(a,c,b,null));
        d.setCostStatus("UNCALCULABLE"); assertFalse(UserModelUsageContributionPolicy.equivalent(a,c,b,d)); d.setCostStatus("CALCULATED");
        d.setCurrency("USD"); assertFalse(UserModelUsageContributionPolicy.equivalent(a,c,b,d)); d.setCurrency("CNY");
        d.setEstimatedCostFen(null); assertFalse(UserModelUsageContributionPolicy.equivalent(a,c,b,d)); c.setEstimatedCostFen(null); assertTrue(UserModelUsageContributionPolicy.equivalent(a,c,b,d));
        d.setEstimatedCostFen(BigDecimal.ZERO); assertFalse(UserModelUsageContributionPolicy.equivalent(a,c,b,d));
        assertThrows(IllegalArgumentException.class,()->UserModelUsageContributionPolicy.checkTotal(BigDecimal.ONE.negate()));
    }
}
