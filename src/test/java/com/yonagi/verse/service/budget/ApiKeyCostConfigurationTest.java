package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.CostBudgetErrorCodeEnum;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.ApiKeyCreateReqDTO;
import com.yonagi.verse.dto.req.CostLimitPatch;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.impl.ApiKeyServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 创建可直接开启成本限额；仍拒绝计费链路关闭或 Key 数据不完整。 */
class ApiKeyCostConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ApiKeyMapper keys = mock(ApiKeyMapper.class);
    private final TenantMapper tenants = mock(TenantMapper.class);
    private final CostBudgetInvocationMapper invocations = mock(CostBudgetInvocationMapper.class);
    private final BudgetExecutionRegistry registry = mock(BudgetExecutionRegistry.class);
    private final UsageOutboxProperties outbox = new UsageOutboxProperties();
    private CostBudgetService budget;
    private ApiKeyServiceImpl service;

    @BeforeAll static void tableMetadata() {
        for (Class<?> type : List.of(ApiKeyDO.class, TenantDO.class))
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "key-cost-config"), type);
    }

    @BeforeEach void setup() {
        budget = new CostBudgetService(keys, invocations, mock(CostBudgetSettlementMapper.class), mock(CostBudgetPeriodMapper.class),
                new BudgetPeriodResolver(), registry, mock(UsageOutboxStager.class), outbox,
                mock(PlatformTransactionManager.class), mock(CostBudgetAudit.class));
        ReflectionTestUtils.setField(budget, "costingEnabled", true);
        when(invocations.unfinished(anyLong(), anyLong(), any())).thenReturn(List.of());
        when(tenants.selectOne(any())).thenReturn(new TenantDO());
        var members = mock(UserTenantService.class);
        when(members.isUserJoinedTenant(1L, 2L)).thenReturn(true);
        when(keys.insert(any(ApiKeyDO.class))).thenReturn(1);
        when(keys.lockActiveKeyOwner(1L)).thenReturn(1L);
        service = new ApiKeyServiceImpl(com.yonagi.verse.support.AccessTestSupport.tenant(tenants, members),
                budget,
                tenants,
                members,
                mock(StringRedisTemplate.class));
        ReflectionTestUtils.setField(service, "baseMapper", keys);
    }

    private ApiKeyCreateReqDTO request() throws Exception {
        return json.readValue("""
                {"name":"预算测试","costLimit":{"enabled":true,"dailyLimitFen":"100"}}
                """, ApiKeyCreateReqDTO.class);
    }

    @Test void creatingKeyCanEnableBudgetWithoutAnyExtraConfigurationSwitch() throws Exception {
        var response = service.createApiKey(1L, 2L, request());
        assertTrue(response.getCostLimit().enabled());
        assertEquals("100", response.getCostLimit().dailyLimitFen());
        assertEquals("1", response.getCostLimit().version());
        var inserted = ArgumentCaptor.forClass(ApiKeyDO.class);
        verify(keys).insert(inserted.capture());
        assertEquals("READY", inserted.getValue().getCostDataState());
        assertEquals(1, inserted.getValue().getStatus());
    }

    @ParameterizedTest @CsvSource({"false,true", "true,false"})
    void disabledCostingOrOutboxStillPreventsEnabling(boolean costingEnabled, boolean outboxEnabled) throws Exception {
        ReflectionTestUtils.setField(budget, "costingEnabled", costingEnabled);
        outbox.setEnabled(outboxEnabled);
        ClientException error = assertThrows(ClientException.class, () -> service.createApiKey(1L, 2L, request()));
        assertEquals(CostBudgetErrorCodeEnum.NOT_READY.code(), error.getErrorCode());
        verify(keys, never()).insert(any(ApiKeyDO.class));
    }

    @ParameterizedTest @ValueSource(strings = {"INITIALIZING", "UNAVAILABLE"})
    void incompleteKeyCannotEnableButCanCloseBudget(String state) throws Exception {
        ApiKeyDO key = existingKey(state);
        var enable = json.readValue("{\"enabled\":true,\"dailyLimitFen\":\"100\"}", CostLimitPatch.class);
        ClientException error = assertThrows(ClientException.class, () -> budget.merge(key, enable));
        assertEquals(CostBudgetErrorCodeEnum.NOT_READY.code(), error.getErrorCode());
        budget.merge(key, json.readValue("{\"enabled\":false}", CostLimitPatch.class));
        assertFalse(key.getCostLimitEnabled());
        assertEquals(state, key.getCostDataState());
    }

    @Test void unconfirmedInvocationStillPreventsEnabling() throws Exception {
        ApiKeyDO key = existingKey("READY");
        CostBudgetInvocationDO invocation = new CostBudgetInvocationDO(); invocation.setState("RUNNING");
        when(invocations.unfinished(anyLong(), anyLong(), any())).thenReturn(List.of(invocation));
        var patch = json.readValue("{\"enabled\":true,\"dailyLimitFen\":\"100\"}", CostLimitPatch.class);
        ClientException error = assertThrows(ClientException.class, () -> budget.merge(key, patch));
        assertEquals(CostBudgetErrorCodeEnum.NOT_READY.code(), error.getErrorCode());
        verify(registry).confirm(invocation);
    }

    private ApiKeyDO existingKey(String state) {
        ApiKeyDO key = new ApiKeyDO(); key.setApiKeyId(3L); key.setTenantId(2L); key.setUserId(1L);
        key.setCostLimitEnabled(false); key.setCostConfigVersion(0L); key.setCostDataState(state);
        return key;
    }
}
