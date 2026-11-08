package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantActivityType;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.TenantMemberRoleUpdateReqDTO;
import com.yonagi.verse.service.CurrentTenantStateService;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.TenantApprovalService;
import com.yonagi.verse.service.TenantInviteService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.helper.TenantValidationHelper;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantMembershipServiceImplTest {
    private static final List<String> ROLES = List.of("SUPER_ADMIN", "ADMIN", "MEMBER");
    private static final Map<String, Integer> RANKS = Map.of("SUPER_ADMIN", 3, "ADMIN", 2, "MEMBER", 1);
    private final UserTenantService memberships = mock(UserTenantService.class);
    private final UserMapper users = mock(UserMapper.class);
    private final TenantValidationHelper validation = mock(TenantValidationHelper.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final CurrentTenantStateService currentTenant = mock(CurrentTenantStateService.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final TenantActivityRecorder activity = mock(TenantActivityRecorder.class);
    private final TenantMembershipServiceImpl service = new TenantMembershipServiceImpl(
            mock(TenantAccessPolicy.class), memberships, users, mock(TenantMapper.class), validation,
            notifications, mock(TenantInviteService.class), mock(TenantApprovalService.class),
            currentTenant, redis, activity);

    @BeforeAll
    static void lambdaMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "member-permission"),
                UserDO.class);
    }

    @BeforeEach
    void validTeamAndMembers() {
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        tenant.setName("team");
        tenant.setType("TEAM");
        tenant.setStatus(1);
        tenant.setDelFlag(0);
        when(validation.validateTenantTeamActive(eq(20L), any())).thenReturn(tenant);
        when(memberships.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        when(memberships.isUserJoinedTenant(30L, 20L)).thenReturn(true);
        when(memberships.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn("MEMBER");
        when(memberships.getRoleByUserIdAndTenantId(30L, 20L)).thenReturn("MEMBER");
    }

    @Test
    void ordinaryMemberCannotRemoveAnotherMemberThroughService() {
        ClientException denied = assertThrows(ClientException.class, () -> service.removeMember(10L, 20L, 30L));
        assertEquals(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_REMOVE.code(), denied.getErrorCode());
        verifyNoInteractions(currentTenant, notifications, activity, redis);
    }

    @Test
    void ordinaryMemberCannotSubmitEvenAnUnchangedMemberRole() {
        TenantMemberRoleUpdateReqDTO request = new TenantMemberRoleUpdateReqDTO();
        request.setNewRole("MEMBER");
        ClientException denied = assertThrows(ClientException.class,
                () -> service.updateMemberRole(10L, 20L, 30L, request));
        assertEquals(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_UPDATE.code(), denied.getErrorCode());
        verifyNoInteractions(currentTenant, notifications, activity, redis);
    }

    @ParameterizedTest
    @MethodSource("removalMatrix")
    void removeMemberRespectsOperatorAndTargetRoles(String operator, String member, boolean allowed) {
        roles(operator, member);
        if (!allowed) {
            ClientException denied = assertThrows(ClientException.class, () -> service.removeMember(10L, 20L, 30L));
            assertEquals(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_REMOVE.code(), denied.getErrorCode());
            noMutation();
            return;
        }

        assertTrue(service.removeMember(10L, 20L, 30L));

        verify(currentTenant).removeMembershipAndFallback(30L, 20L);
        TenantActivityDraft event = recorded(TenantActivityType.MEMBER_REMOVED);
        assertTrue(event.details().isEmpty());
        verify(redis).delete(RedisKeyConstant.USER_TENANT_RELATION_KEY + "30:20");
        verify(notifications).publishNotification(eq(20L), eq("SYSTEM"), eq("WARNING"),
                eq("已被移出租户"), anyString(), isNull(), eq(List.of(30L)));
    }

    @ParameterizedTest
    @MethodSource("roleChangeMatrix")
    void roleChangesCannotManageOrGrantAnEqualOrHigherRole(String operator, String member,
                                                          String newRole, boolean allowed) {
        roles(operator, member);
        TenantMemberRoleUpdateReqDTO request = roleRequest(newRole);
        if (!allowed) {
            ClientException denied = assertThrows(ClientException.class,
                    () -> service.updateMemberRole(10L, 20L, 30L, request));
            assertEquals(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_UPDATE.code(), denied.getErrorCode());
            noMutation();
            return;
        }

        assertTrue(service.updateMemberRole(10L, 20L, 30L, request));

        verify(memberships).updateUserRole(30L, 20L, member, newRole);
        TenantActivityDraft event = recorded(TenantActivityType.MEMBER_ROLE_CHANGED);
        assertEquals(Map.of("oldRole", member, "newRole", newRole), event.details());
        verify(notifications).publishNotification(eq(20L), eq("SYSTEM"), eq("INFO"),
                eq("角色已变更"), anyString(), isNull(), eq(List.of(30L)));
        verifyNoInteractions(currentTenant, redis);
    }

    @ParameterizedTest
    @ValueSource(longs = {10L, 30L})
    void absentOperatorOrTargetKeepsExistingRejectionWithoutSideEffects(Long absentUser) {
        roles("SUPER_ADMIN", "MEMBER");
        when(memberships.isUserJoinedTenant(absentUser, 20L)).thenReturn(false);
        TenantErrorCodeEnum code = absentUser == 10L
                ? TenantErrorCodeEnum.TENANT_NOT_JOINED : TenantErrorCodeEnum.TENANT_MEMBER_NOT_JOINED;
        assertEquals(code.code(), assertThrows(ClientException.class,
                () -> service.removeMember(10L, 20L, 30L)).getErrorCode());
        assertEquals(code.code(), assertThrows(ClientException.class,
                () -> service.updateMemberRole(10L, 20L, 30L, roleRequest("MEMBER"))).getErrorCode());
        noMutation();
    }

    @Test
    void roleReadFailureCannotBecomePermissionOrSuccess() {
        IllegalStateException unavailable = new IllegalStateException("role storage unavailable");
        when(memberships.getRoleByUserIdAndTenantId(10L, 20L)).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> service.removeMember(10L, 20L, 30L)));
        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> service.updateMemberRole(10L, 20L, 30L, roleRequest("MEMBER"))));
        noMutation();
    }

    @Test
    void failedRoleWriteDoesNotPublishSuccessActivityOrNotification() {
        roles("SUPER_ADMIN", "ADMIN");
        IllegalStateException unavailable = new IllegalStateException("member update failed");
        doThrow(unavailable).when(memberships).updateUserRole(30L, 20L, "ADMIN", "MEMBER");
        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> service.updateMemberRole(10L, 20L, 30L, roleRequest("MEMBER"))));
        verifyNoInteractions(users, currentTenant, notifications, activity, redis);
    }

    @Test
    void failedRemovalDoesNotPublishSuccessActivityOrNotification() {
        roles("ADMIN", "MEMBER");
        IllegalStateException unavailable = new IllegalStateException("member removal failed");
        when(currentTenant.removeMembershipAndFallback(30L, 20L)).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class,
                () -> service.removeMember(10L, 20L, 30L)));
        verifyNoInteractions(users, notifications, activity, redis);
    }

    @Test
    void sameAccountCannotManageItsOwnRoleThroughService() {
        roles("SUPER_ADMIN", "MEMBER");
        assertEquals(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_REMOVE.code(), assertThrows(ClientException.class,
                () -> service.removeMember(10L, 20L, 10L)).getErrorCode());
        assertEquals(TenantErrorCodeEnum.TENANT_MEMBER_CAN_NOT_UPDATE.code(), assertThrows(ClientException.class,
                () -> service.updateMemberRole(10L, 20L, 10L, roleRequest("ADMIN"))).getErrorCode());
        noMutation();
    }

    private void roles(String operator, String member) {
        when(memberships.getRoleByUserIdAndTenantId(10L, 20L)).thenReturn(operator);
        when(memberships.getRoleByUserIdAndTenantId(30L, 20L)).thenReturn(member);
    }

    private void noMutation() {
        verify(memberships, never()).updateUserRole(any(), any(), any(), any());
        verifyNoInteractions(users, currentTenant, notifications, activity, redis);
    }

    private TenantActivityDraft recorded(TenantActivityType type) {
        ArgumentCaptor<TenantActivityDraft> captor = ArgumentCaptor.forClass(TenantActivityDraft.class);
        verify(activity).record(eq(20L), captor.capture());
        TenantActivityDraft event = captor.getValue();
        assertEquals(type, event.type());
        assertEquals(10L, event.actorUserId());
        assertEquals("30", event.targetId());
        return event;
    }

    private static TenantMemberRoleUpdateReqDTO roleRequest(String role) {
        TenantMemberRoleUpdateReqDTO request = new TenantMemberRoleUpdateReqDTO();
        request.setNewRole(role);
        return request;
    }

    private static Stream<Arguments> removalMatrix() {
        List<Arguments> cases = new ArrayList<>();
        for (String operator : ROLES) {
            for (String member : ROLES) {
                cases.add(Arguments.of(operator, member, RANKS.get(operator) > 1
                        && RANKS.get(operator) > RANKS.get(member)));
            }
        }
        return cases.stream();
    }

    private static Stream<Arguments> roleChangeMatrix() {
        List<Arguments> cases = new ArrayList<>();
        for (String operator : ROLES) {
            for (String member : ROLES) {
                for (String granted : ROLES) {
                    cases.add(Arguments.of(operator, member, granted, RANKS.get(operator) > 1
                            && RANKS.get(operator) > RANKS.get(member) && RANKS.get(operator) > RANKS.get(granted)));
                }
            }
        }
        return cases.stream();
    }
}
