package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantActivityLogDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantActivityLogMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dto.resp.TenantActivityListRespDTO;
import com.yonagi.verse.dto.resp.TenantActivityStatusRespDTO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class TenantActivityQueryServiceImplTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "activity-tenant"), TenantDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "activity-member"), UserTenantDO.class);
    }

    @Test
    void statusReturnsCurrentSwitchForEveryActiveMemberRole() {
        for (String role : List.of("MEMBER", "ADMIN", "SUPER_ADMIN")) {
            Fixture enabled = fixture(activeTenant(true), membership(role));
            TenantActivityStatusRespDTO enabledStatus = enabled.service.getStatus(10L, 20L);
            assertTrue(enabledStatus.getEnabled());

            Fixture disabled = fixture(activeTenant(false), membership(role));
            TenantActivityStatusRespDTO disabledStatus = disabled.service.getStatus(10L, 20L);
            assertFalse(disabledStatus.getEnabled());
        }
    }

    @Test
    void statusRejectsUnknownTenantAndNonMember() {
        Fixture unknownTenant = fixture(null, membership("MEMBER"));
        ClientException missing = assertThrows(ClientException.class,
                () -> unknownTenant.service.getStatus(10L, 20L));
        assertEquals(TenantErrorCodeEnum.TENANT_NOT_EXIST.code(), missing.getErrorCode());
        verifyNoInteractions(unknownTenant.userTenantMapper);

        Fixture nonMember = fixture(activeTenant(true), null);
        ClientException denied = assertThrows(ClientException.class,
                () -> nonMember.service.getStatus(10L, 20L));
        assertEquals(TenantErrorCodeEnum.TENANT_NOT_JOINED.code(), denied.getErrorCode());
    }

    @Test
    void disabledTenantNeverReadsHistoricalRows() {
        Fixture fixture = fixture(activeTenant(false), membership("MEMBER"));

        ClientException exception = assertThrows(ClientException.class,
                () -> fixture.service.listActivities(10L, 20L, 30, null));

        assertEquals(TenantErrorCodeEnum.TENANT_ACTIVITY_RECORDING_DISABLED.code(), exception.getErrorCode());
        verifyNoInteractions(fixture.activityLogMapper);
    }

    @Test
    void validatesDefaultAndBoundaryLimitsBeforeQuerying() {
        Fixture fixture = fixture(activeTenant(true), membership("MEMBER"));
        when(fixture.activityLogMapper.selectTimeline(anyLong(), any(), any(), anyInt()))
                .thenReturn(List.of());

        fixture.service.listActivities(10L, 20L, null, null);
        verify(fixture.activityLogMapper).selectTimeline(20L, null, null, 31);

        fixture.service.listActivities(10L, 20L, 1, null);
        verify(fixture.activityLogMapper).selectTimeline(20L, null, null, 2);

        fixture.service.listActivities(10L, 20L, 50, null);
        verify(fixture.activityLogMapper).selectTimeline(20L, null, null, 51);

        assertThrows(ClientException.class,
                () -> fixture.service.listActivities(10L, 20L, 0, null));
        assertThrows(ClientException.class,
                () -> fixture.service.listActivities(10L, 20L, 51, null));
    }

    @Test
    void limitPlusOneBuildsCursorAndNextBatchUsesStableTuple() {
        Fixture fixture = fixture(activeTenant(true), membership("MEMBER"));
        LocalDateTime newest = LocalDateTime.of(2026, 9, 19, 12, 0);
        List<TenantActivityLogDO> firstRows = List.of(
                row(30L, "event-30", newest),
                row(29L, "event-29", newest),
                row(28L, "event-28", newest.minusSeconds(1)));
        when(fixture.activityLogMapper.selectTimeline(anyLong(), any(), any(), anyInt()))
                .thenReturn(firstRows, List.of(row(28L, "event-28", newest.minusSeconds(1))));

        TenantActivityListRespDTO first = fixture.service.listActivities(10L, 20L, 2, null);
        assertEquals(List.of("event-30", "event-29"),
                first.getItems().stream().map(item -> item.getEventId()).toList());
        assertTrue(first.getHasMore());
        assertTrue(first.getNextCursor() != null && !first.getNextCursor().isBlank());

        TenantActivityListRespDTO second = fixture.service.listActivities(
                10L, 20L, 2, first.getNextCursor());
        assertEquals(List.of("event-28"),
                second.getItems().stream().map(item -> item.getEventId()).toList());
        assertFalse(second.getHasMore());
        assertNull(second.getNextCursor());

        ArgumentCaptor<LocalDateTime> occurredAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Long> id = ArgumentCaptor.forClass(Long.class);
        verify(fixture.activityLogMapper, times(2)).selectTimeline(
                org.mockito.ArgumentMatchers.eq(20L), occurredAt.capture(), id.capture(),
                org.mockito.ArgumentMatchers.eq(3));
        assertNull(occurredAt.getAllValues().get(0));
        assertNull(id.getAllValues().get(0));
        assertEquals(newest, occurredAt.getAllValues().get(1));
        assertEquals(29L, id.getAllValues().get(1));
    }

    @Test
    void invalidCursorIsRejectedInsteadOfRestartingFromFirstPage() {
        Fixture fixture = fixture(activeTenant(true), membership("MEMBER"));

        assertThrows(ClientException.class,
                () -> fixture.service.listActivities(10L, 20L, 30, "not-base64"));
        assertThrows(ClientException.class,
                () -> fixture.service.listActivities(10L, 20L, 30, ""));

        verify(fixture.activityLogMapper, never()).selectTimeline(anyLong(), any(), any(), anyInt());
    }

    @Test
    void responseUsesSnapshotsStringIdsAndWhitelistedStructuredDetails() throws Exception {
        Fixture fixture = fixture(activeTenant(true), membership("MEMBER"));
        TenantActivityLogDO row = row(9L, "event-9", LocalDateTime.of(2026, 9, 19, 8, 30));
        row.setActivityType("MEMBER_ROLE_CHANGED");
        row.setCategory("MEMBER");
        row.setActorUserId(9_007_199_254_740_993L);
        row.setActorUsername("snapshot-user");
        row.setActorNickname("快照昵称");
        row.setCurrentActorNickname("当前昵称");
        row.setTargetType("MEMBER");
        row.setTargetId("9007199254740995");
        row.setTargetName("已离开成员");
        row.setDetailJson("{\"oldRole\":\"MEMBER\",\"newRole\":\"ADMIN\",\"secret\":\"hidden\"}");
        when(fixture.activityLogMapper.selectTimeline(anyLong(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(row));

        TenantActivityListRespDTO response = fixture.service.listActivities(10L, 20L, 30, null);

        assertEquals("当前昵称", response.getItems().getFirst().getActorNickname());
        assertEquals("已离开成员", response.getItems().getFirst().getTargetName());
        assertEquals(2, response.getItems().getFirst().getDetails().size());
        assertTrue(response.getItems().getFirst().getDetails().keySet()
                .containsAll(List.of("oldRole", "newRole")));
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);
        assertTrue(json.contains("\"actorUserId\":\"9007199254740993\""));
        assertFalse(json.contains("secret"));
    }

    @Test
    void responseFallsBackToNicknameSnapshotWhenActorIsNoLongerAMember() {
        Fixture fixture = fixture(activeTenant(true), membership("MEMBER"));
        TenantActivityLogDO row = row(9L, "event-9", LocalDateTime.of(2026, 9, 19, 8, 30));
        row.setActorNickname("离开前昵称");
        when(fixture.activityLogMapper.selectTimeline(anyLong(), isNull(), isNull(), anyInt()))
                .thenReturn(List.of(row));

        TenantActivityListRespDTO response = fixture.service.listActivities(10L, 20L, 30, null);

        assertEquals("离开前昵称", response.getItems().getFirst().getActorNickname());
    }

    private static Fixture fixture(TenantDO tenant, UserTenantDO membership) {
        TenantMapper tenantMapper = mock(TenantMapper.class);
        UserTenantMapper userTenantMapper = mock(UserTenantMapper.class);
        TenantActivityLogMapper activityLogMapper = mock(TenantActivityLogMapper.class);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(userTenantMapper.selectOne(any())).thenReturn(membership);
        return new Fixture(tenantMapper, userTenantMapper, activityLogMapper,
                new TenantActivityQueryServiceImpl(tenantMapper, userTenantMapper, activityLogMapper));
    }

    private static TenantDO activeTenant(boolean enabled) {
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        tenant.setStatus(1);
        tenant.setActivityRecordingEnabled(enabled ? 1 : 0);
        return tenant;
    }

    private static UserTenantDO membership(String role) {
        UserTenantDO membership = new UserTenantDO();
        membership.setUserId(10L);
        membership.setTenantId(20L);
        membership.setRole(role);
        return membership;
    }

    private static TenantActivityLogDO row(Long id, String eventId, LocalDateTime occurredAt) {
        TenantActivityLogDO row = new TenantActivityLogDO();
        row.setId(id);
        row.setEventId(eventId);
        row.setCategory("TENANT");
        row.setActivityType("TENANT_SETTINGS_UPDATED");
        row.setActorUserId(10L);
        row.setActorUsername("alice");
        row.setTargetType("TENANT");
        row.setTargetId("20");
        row.setTargetName("Verse");
        row.setDetailJson("{}");
        row.setOccurredAt(occurredAt);
        return row;
    }

    private record Fixture(TenantMapper tenantMapper,
                           UserTenantMapper userTenantMapper,
                           TenantActivityLogMapper activityLogMapper,
                           TenantActivityQueryServiceImpl service) {
    }
}
