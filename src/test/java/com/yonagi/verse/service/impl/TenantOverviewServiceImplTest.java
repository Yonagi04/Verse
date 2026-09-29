package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.TenantOverviewMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dao.projection.TenantOverviewCountRow;
import com.yonagi.verse.dao.projection.TenantOverviewUsageRow;
import com.yonagi.verse.dto.resp.TenantOverviewRespDTO;
import com.yonagi.verse.service.UserTenantService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TenantOverviewServiceImplTest {
    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "overview"), TenantDO.class);
    }

    @Test
    void batchUsesEachTargetRoleAndFixedNumberOfAggregateQueries() {
        Fixture fixture = fixture();
        when(fixture.memberships.getUserTenantList(10L, false, 10L))
                .thenReturn(List.of(member(20L, "ADMIN"), member(21L, "MEMBER")));
        when(fixture.tenants.selectList(any())).thenReturn(List.of(tenant(20L, "TEAM", 0), tenant(21L, "TEAM", 0)));
        when(fixture.overview.memberCounts(anyList())).thenReturn(List.of(count(20L, 4L), count(21L, 2L)));
        when(fixture.overview.availableServiceCounts(anyList())).thenReturn(List.of(count(20L, 3L)));
        when(fixture.overview.pendingJoinCounts(List.of(20L))).thenReturn(List.of(count(20L, 2L)));
        when(fixture.overview.usage(eq(List.of(20L)), isNull(), any(), any())).thenReturn(List.of(usage(20L, 100L, 6L, 6L, 0L)));
        when(fixture.overview.usage(eq(List.of(21L)), eq(10L), any(), any())).thenReturn(List.of(usage(21L, 7L, 1L, 0L, 1L)));

        TenantOverviewRespDTO.Batch result = fixture.service.batch(10L);

        assertEquals(2, result.getItems().size());
        assertEquals("TENANT", result.getItems().get(0).getUsage().getScope());
        assertEquals("100", result.getItems().get(0).getUsage().getTotalTokens());
        assertEquals(2L, result.getItems().get(0).getPendingJoinRequestCount());
        assertEquals("SELF", result.getItems().get(1).getUsage().getScope());
        assertNull(result.getItems().get(1).getUsage().getTotalTokens());
        assertEquals("PARTIAL", result.getItems().get(1).getUsage().getTokenQuality());
        assertNull(result.getItems().get(1).getPendingJoinRequestCount());
        assertEquals(0L, result.getItems().get(1).getAvailableServiceCount());
        verify(fixture.overview, times(2)).usage(anyList(), nullable(Long.class), any(), any());
        verify(fixture.overview, times(1)).pendingJoinCounts(anyList());
    }

    @Test
    void detailRejectsRevokedMembershipBeforeAnyAggregate() {
        Fixture fixture = fixture();
        when(fixture.tenants.selectOne(any())).thenReturn(tenant(21L, "TEAM", 1));

        assertThrows(ClientException.class, () -> fixture.service.detail(10L, 21L));
        verifyNoInteractions(fixture.overview);
    }

    @Test
    void disabledActivityDoesNotReadTimelineAndAggregateFailureIsNull() {
        Fixture fixture = fixture();
        when(fixture.tenants.selectOne(any())).thenReturn(tenant(21L, "TEAM", 0));
        when(fixture.userTenants.selectActiveMembership(10L, 21L)).thenReturn(member(21L, "MEMBER"));
        when(fixture.overview.memberCounts(anyList())).thenThrow(new IllegalStateException("counts unavailable"));
        when(fixture.overview.availableServiceCounts(anyList())).thenReturn(List.of());
        when(fixture.overview.usage(anyList(), eq(10L), any(), any())).thenReturn(List.of());

        TenantOverviewRespDTO.Detail result = fixture.service.detail(10L, 21L);

        assertNull(result.getMemberCount());
        assertEquals(0L, result.getAvailableServiceCount());
        assertEquals("0", result.getUsage().getTotalTokens());
        assertNull(result.getRecentActivities());
        verify(fixture.overview, never()).recentActivities(any(), anyList());
    }

    @Test
    void windowContainsTodayAndPreviousTwentyNineShanghaiCalendarDays() {
        TenantOverviewServiceImpl.Window window = TenantOverviewServiceImpl.window(LocalDate.of(2026, 10, 1));
        assertEquals(LocalDateTime.of(2026, 9, 2, 0, 0), window.from());
        assertEquals(LocalDateTime.of(2026, 10, 2, 0, 0), window.to());
        assertEquals("2026-09-02T00:00+08:00", window.fromIso());
    }

    private static Fixture fixture() {
        UserTenantService memberships = mock(UserTenantService.class);
        UserTenantMapper userTenants = mock(UserTenantMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        TenantOverviewMapper overview = mock(TenantOverviewMapper.class);
        UsageReportingProperties properties = new UsageReportingProperties();
        return new Fixture(memberships, userTenants, tenants, overview,
                new TenantOverviewServiceImpl(memberships, userTenants, tenants, overview, properties));
    }

    private static UserTenantDO member(Long tenantId, String role) {
        UserTenantDO value = new UserTenantDO();
        value.setTenantId(tenantId);
        value.setRole(role);
        return value;
    }

    private static TenantDO tenant(Long tenantId, String type, int activityEnabled) {
        TenantDO value = new TenantDO();
        value.setTenantId(tenantId);
        value.setType(type);
        value.setActivityRecordingEnabled(activityEnabled);
        return value;
    }

    private static TenantOverviewCountRow count(Long tenantId, Long total) {
        TenantOverviewCountRow value = new TenantOverviewCountRow();
        value.setTenantId(tenantId);
        value.setTotal(total);
        return value;
    }

    private static TenantOverviewUsageRow usage(Long tenantId, Long tokens, Long requests, Long exact, Long unknown) {
        TenantOverviewUsageRow value = new TenantOverviewUsageRow();
        value.setTenantId(tenantId);
        value.setTotalTokens(tokens);
        value.setRequestCount(requests);
        value.setExactUsageCount(exact);
        value.setUnknownUsageCount(unknown);
        value.setMaxUpdatedAt(LocalDateTime.of(2026, 9, 29, 10, 0));
        return value;
    }

    private record Fixture(UserTenantService memberships, UserTenantMapper userTenants,
                           TenantMapper tenants, TenantOverviewMapper overview,
                           TenantOverviewServiceImpl service) { }
}
