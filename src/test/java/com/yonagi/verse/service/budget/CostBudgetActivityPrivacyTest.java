package com.yonagi.verse.service.budget;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.CostLimitPatch;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 装配真实预算组件，验证个人限额配置不依赖租户动态的可用性。 */
class CostBudgetActivityPrivacyTest {
    private final TenantActivityRecorder activities = mock(TenantActivityRecorder.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(BudgetComponents.class)
            .withBean(TenantCostBudgetLedger.class, () -> mock(TenantCostBudgetLedger.class))
            .withBean(UserMapper.class, () -> mock(UserMapper.class))
            .withBean(ApiKeyMapper.class, () -> mock(ApiKeyMapper.class))
            .withBean(CostBudgetInvocationMapper.class, () -> mock(CostBudgetInvocationMapper.class))
            .withBean(CostBudgetSettlementMapper.class, () -> mock(CostBudgetSettlementMapper.class))
            .withBean(CostBudgetPeriodMapper.class, () -> mock(CostBudgetPeriodMapper.class))
            .withBean(BudgetPeriodResolver.class, BudgetPeriodResolver::new)
            .withBean(BudgetExecutionRegistry.class, () -> mock(BudgetExecutionRegistry.class))
            .withBean(UsageOutboxStager.class, () -> mock(UsageOutboxStager.class))
            .withBean(LlmCostBudgetLedger.class, () -> mock(LlmCostBudgetLedger.class))
            .withBean(io.micrometer.core.instrument.MeterRegistry.class, () -> new io.micrometer.core.instrument.simple.SimpleMeterRegistry())
            .withBean(UsageOutboxProperties.class, UsageOutboxProperties::new)
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(TenantActivityRecorder.class, () -> activities);

    @TestConfiguration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Import(CostBudgetService.class)
    static class BudgetComponents { }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuringPersonalBudgetDoesNotWriteTenantActivity(boolean activityUnavailable) {
        if (activityUnavailable) {
            when(activities.record(anyLong(), any())).thenThrow(new IllegalStateException("activity outbox unavailable"));
        }
        context.run(application -> {
            assertNull(application.getStartupFailure());
            CostBudgetService budget = application.getBean(CostBudgetService.class);
            ApiKeyDO key = new ApiKeyDO();
            key.setApiKeyId(3L); key.setTenantId(2L); key.setUserId(1L);
            key.setCostLimitEnabled(false); key.setCostConfigVersion(0L);
            ObjectMapper json = new ObjectMapper();

            // 新建时开启、调整、重复提交和关闭都只影响个人配置。
            budget.merge(key, json.readValue("{\"enabled\":true,\"dailyLimitFen\":\"100\"}", CostLimitPatch.class));
            assertTrue(key.getCostLimitEnabled());
            assertEquals(1L, key.getCostConfigVersion());
            budget.merge(key, json.readValue("{\"dailyLimitFen\":\"200\",\"expectedVersion\":\"1\"}", CostLimitPatch.class));
            assertEquals(new BigDecimal("200"), key.getCostLimitDailyFen());
            assertEquals(2L, key.getCostConfigVersion());
            budget.merge(key, json.readValue("{\"dailyLimitFen\":\"200\"}", CostLimitPatch.class));
            assertEquals(2L, key.getCostConfigVersion());
            budget.merge(key, json.readValue("{\"enabled\":false,\"dailyLimitFen\":null}", CostLimitPatch.class));
            assertFalse(key.getCostLimitEnabled());
            assertNull(key.getCostLimitDailyFen());
            assertEquals(3L, key.getCostConfigVersion());
            verifyNoInteractions(activities);
        });
    }
}
