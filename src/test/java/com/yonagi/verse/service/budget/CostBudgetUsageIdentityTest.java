package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.async.outbox.UsageOutboxStager;
import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.mapper.*;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostBudgetUsageIdentityTest {
    final CostBudgetInvocationMapper invocations=mock(CostBudgetInvocationMapper.class);
    final CostBudgetSettlementMapper settlements=mock(CostBudgetSettlementMapper.class);
    final UsageOutboxStager outbox=mock(UsageOutboxStager.class);
    final CostBudgetService service=new CostBudgetService(mock(ApiKeyMapper.class),invocations,settlements,mock(CostBudgetPeriodMapper.class),
            new BudgetPeriodResolver(Clock.systemUTC()),mock(BudgetExecutionRegistry.class),outbox,new UsageOutboxProperties(),mock(PlatformTransactionManager.class),
            mock(LlmCostBudgetLedger.class),mock(TenantCostBudgetLedger.class),mock(UserMapper.class));
    TokenUsageEvent event;
    @BeforeEach void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"usage-identity"),CostBudgetInvocationDO.class);
        event=new TokenUsageEvent(); event.setSource("PLAYGROUND"); event.setTenantId(2L); event.setRequestId("r");
        event.setRequestStartedAt(Instant.parse("2026-10-18T04:00:00.123999Z"));
    }

    @Test void existingCanonicalIdentitySurvivesStoredMillisecondPrecisionWithoutChangingBudget() {
        var row=invocation(); when(invocations.selectOne(any())).thenReturn(row);
        assertEquals("canonical-event",service.usageEventId(event)); verifyNoInteractions(settlements,outbox);
        verify(invocations,never()).updateById(any()); verify(invocations,never()).insert(any(CostBudgetInvocationDO.class));
    }

    @Test void differentOwnershipOrStartTimeCannotReuseAnotherInvocationIdentity() {
        List<Consumer<CostBudgetInvocationDO>> changes=List.of(row->row.setTenantId(3L),row->row.setApiKeyId(4L),row->row.setSource("API_KEY"),
                row->row.setRequestStartedAt(null),row->row.setRequestStartedAt(row.getRequestStartedAt().plusSeconds(1)));
        for (var change:changes) {
            var row=invocation(); change.accept(row); when(invocations.selectOne(any())).thenReturn(row);
            assertEquals("USAGE_IDENTITY_CONFLICT",assertThrows(IllegalStateException.class,()->service.usageEventId(event)).getMessage());
        }
        verifyNoInteractions(settlements,outbox);
    }

    @Test void absentBudgetInvocationUsesStableSourceTenantRequestIdentity() {
        var first=service.usageEventId(event); assertEquals(64,first.length()); assertEquals(first,service.usageEventId(event));
        event.setTenantId(3L); assertNotEquals(first,service.usageEventId(event)); event.setTenantId(2L); event.setSource("API_KEY"); assertNotEquals(first,service.usageEventId(event));
        event.setSource("PLAYGROUND"); event.setRequestId("other"); assertNotEquals(first,service.usageEventId(event)); verifyNoInteractions(settlements,outbox);
    }

    private CostBudgetInvocationDO invocation() {
        var row=new CostBudgetInvocationDO(); row.setEventId("canonical-event"); row.setRequestId("r"); row.setTenantId(2L); row.setSource("PLAYGROUND");
        row.setRequestStartedAt(Instant.ofEpochMilli(event.getRequestStartedAt().toEpochMilli()).atZone(BudgetPeriodResolver.ZONE).toLocalDateTime()); return row;
    }
}
