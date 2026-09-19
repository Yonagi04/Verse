package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.event.TenantActivityDraft;
import com.yonagi.verse.common.enums.TenantActivityType;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.TenantInviteDO;
import com.yonagi.verse.dao.entity.TenantJoinRequestDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.NotificationMapper;
import com.yonagi.verse.dao.mapper.TenantInviteMapper;
import com.yonagi.verse.dao.mapper.TenantJoinRequestMapper;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.TenantCloseReqDTO;
import com.yonagi.verse.dto.req.TenantInviteReqDTO;
import com.yonagi.verse.dto.req.TenantJoinRejectReqDTO;
import com.yonagi.verse.dto.req.TenantJoinReqDTO;
import com.yonagi.verse.service.CurrentTenantStateService;
import com.yonagi.verse.service.NotificationService;
import com.yonagi.verse.service.TenantApprovalService;
import com.yonagi.verse.service.TenantInviteService;
import com.yonagi.verse.service.TenantMediaService;
import com.yonagi.verse.service.UserTenantService;
import com.yonagi.verse.service.helper.TenantValidationHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RBloomFilter;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantActivityBusinessInstrumentationTest {

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "tenant-activity-business");
        TableInfoHelper.initTableInfo(assistant, TenantDO.class);
        TableInfoHelper.initTableInfo(assistant, TenantInviteDO.class);
        TableInfoHelper.initTableInfo(assistant, TenantJoinRequestDO.class);
        TableInfoHelper.initTableInfo(assistant, UserDO.class);
        TableInfoHelper.initTableInfo(assistant, UserTenantDO.class);
    }

    @Test
    void directJoinUsesJoiningMemberAsBothActorAndTarget() {
        UserTenantService memberships = mock(UserTenantService.class);
        UserMapper users = mock(UserMapper.class);
        TenantMapper tenants = mock(TenantMapper.class);
        TenantInviteService invites = mock(TenantInviteService.class);
        TenantActivityRecorder recorder = mock(TenantActivityRecorder.class);
        TenantInviteDO invite = new TenantInviteDO();
        invite.setId(9L);
        invite.setTenantId(20L);
        TenantDO tenant = teamTenant();
        tenant.setJoinApprovalMode(0);
        UserDO member = user(30L, "member", "成员甲");
        when(memberships.getUserJoinedTenantCount(30L)).thenReturn(0L);
        when(invites.validateAndGetInviteCode("invite-code")).thenReturn(invite);
        when(memberships.isUserJoinedTenant(30L, 20L)).thenReturn(false);
        when(tenants.selectOne(any())).thenReturn(tenant);
        when(memberships.createUserTenant(30L, 20L, "MEMBER")).thenReturn(true);
        when(users.selectOne(any())).thenReturn(member);

        TenantMembershipServiceImpl service = new TenantMembershipServiceImpl(
                memberships, users, tenants, mock(TenantValidationHelper.class),
                mock(NotificationService.class), invites, mock(TenantApprovalService.class),
                mock(CurrentTenantStateService.class), mock(StringRedisTemplate.class), recorder);
        TenantJoinReqDTO request = new TenantJoinReqDTO();
        request.setInviteCode("invite-code");

        assertFalse(service.joinTenant(30L, request).getPendingApproval());

        TenantActivityDraft draft = capture(recorder);
        assertEquals(TenantActivityType.MEMBER_JOINED, draft.type());
        assertEquals(30L, draft.actorUserId());
        assertEquals("30", draft.targetId());
        assertEquals("DIRECT_INVITE", draft.details().get("joinSource"));
        verify(recorder).record(eq(20L), any(TenantActivityDraft.class));
    }

    @Test
    void approvalDistinguishesAdministratorFromApplicantAndRejectOmitsReason() {
        TenantJoinRequestMapper requests = mock(TenantJoinRequestMapper.class);
        TenantValidationHelper validation = mock(TenantValidationHelper.class);
        UserTenantService memberships = mock(UserTenantService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        UserMapper users = mock(UserMapper.class);
        TenantActivityRecorder recorder = mock(TenantActivityRecorder.class);
        TenantDO tenant = teamTenant();
        TenantJoinRequestDO request = new TenantJoinRequestDO();
        request.setRequestId(40L);
        request.setTenantId(20L);
        request.setUserId(30L);
        request.setInviteId(9L);
        request.setStatus("PENDING");
        when(validation.validateTenantTeamActive(eq(20L), any())).thenReturn(tenant);
        when(memberships.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        when(requests.selectOne(any())).thenReturn(request);
        when(requests.update(any())).thenReturn(1);
        when(memberships.createUserTenant(30L, 20L, "MEMBER")).thenReturn(true);
        when(users.selectOne(any())).thenReturn(user(30L, "applicant", "申请人"));
        TenantApprovalServiceImpl service = new TenantApprovalServiceImpl(
                requests, validation, memberships, redis, mock(NotificationService.class),
                mock(TenantInviteService.class), users, mock(TenantInviteMapper.class), recorder);

        assertTrue(service.approveJoinRequest(10L, 20L, 40L));

        TenantActivityDraft approved = capture(recorder);
        assertEquals(TenantActivityType.MEMBER_JOINED, approved.type());
        assertEquals(10L, approved.actorUserId());
        assertEquals("30", approved.targetId());
        assertEquals("APPROVAL", approved.details().get("joinSource"));

        reset(recorder);
        TenantJoinRejectReqDTO rejection = new TenantJoinRejectReqDTO();
        rejection.setReviewComment("一段不应进入动态详情的拒绝原因");
        assertTrue(service.rejectJoinRequest(10L, 20L, 40L, rejection));
        TenantActivityDraft rejected = capture(recorder);
        assertEquals(TenantActivityType.JOIN_REQUEST_REJECTED, rejected.type());
        assertTrue(rejected.details().isEmpty());
    }

    @Test
    void inviteCreateIsNotRecordedButEnableAndDisableAreRecorded() {
        TenantInviteMapper mapper = mock(TenantInviteMapper.class);
        UserTenantService memberships = mock(UserTenantService.class);
        TenantActivityRecorder recorder = mock(TenantActivityRecorder.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        @SuppressWarnings("unchecked")
        RBloomFilter<String> bloom = mock(RBloomFilter.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(memberships.isUserJoinedTenant(10L, 20L)).thenReturn(true);
        when(bloom.contains(anyString())).thenReturn(false);
        when(mapper.insert(any())).thenAnswer(invocation -> {
            TenantInviteDO value = invocation.getArgument(0);
            value.setId(9L);
            return 1;
        });
        when(mapper.update(any())).thenReturn(1);
        TenantInviteServiceImpl service = new TenantInviteServiceImpl(
                mapper, mock(TenantMapper.class), mock(TenantValidationHelper.class), memberships,
                redis, bloom, mock(DomainEventPublisher.class), recorder);
        ReflectionTestUtils.setField(service, "maxInviteCodePerDay", 10);
        ReflectionTestUtils.setField(service, "frontendBaseUrl", "https://example.invalid");
        TenantInviteReqDTO create = new TenantInviteReqDTO();
        create.setExpireAt(new Date(System.currentTimeMillis() + 60_000));

        service.inviteUser(10L, 20L, create);

        verifyNoInteractions(recorder);

        TenantInviteDO invite = new TenantInviteDO();
        invite.setId(9L);
        invite.setTenantId(20L);
        invite.setCode("secret-invite-code");
        invite.setIsActive(1);
        invite.setExpiresAt(create.getExpireAt());
        when(mapper.selectOne(any())).thenReturn(invite);
        service.deactivateInviteCode(10L, 20L, 9L);
        assertEquals(TenantActivityType.INVITE_DISABLED, capture(recorder).type());

        invite.setIsActive(0);
        reset(recorder);
        service.activateInviteCode(10L, 20L, 9L);
        assertEquals(TenantActivityType.INVITE_ENABLED, capture(recorder).type());
    }

    @Test
    void tenantCloseUsesPreCloseNameAndDoesNotRecordFailedClose() {
        TenantMapper tenants = mock(TenantMapper.class);
        UserTenantService memberships = mock(UserTenantService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        JwtUtil jwt = mock(JwtUtil.class);
        TenantValidationHelper validation = mock(TenantValidationHelper.class);
        CurrentTenantStateService currentState = mock(CurrentTenantStateService.class);
        TenantActivityRecorder recorder = mock(TenantActivityRecorder.class);
        TenantDO tenant = teamTenant();
        tenant.setName("停用前名称");
        when(redis.opsForValue().get("verse:tenant:close-token:20-10")).thenReturn("token");
        when(jwt.validateToken("token")).thenReturn(true);
        when(validation.validateTenantTeamActive(eq(20L), any())).thenReturn(tenant);
        when(currentState.closeTenantAndFallback(10L, 20L)).thenReturn(tenant);
        when(memberships.list(any(Wrapper.class))).thenReturn(List.of());
        TenantCrudServiceImpl service = new TenantCrudServiceImpl(
                tenants, memberships, mock(UserMapper.class), redis, jwt,
                mock(NotificationService.class), validation, mock(NotificationMapper.class),
                mock(TenantMediaService.class), currentState, recorder);
        TenantCloseReqDTO close = new TenantCloseReqDTO();
        close.setDisableToken("token");
        close.setConfirmText("停用前名称");

        assertTrue(service.closeTenant(10L, 20L, close));
        TenantActivityDraft disabled = capture(recorder);
        assertEquals(TenantActivityType.TENANT_DISABLED, disabled.type());
        assertEquals("停用前名称", disabled.targetName());

        reset(recorder);
        doThrow(new IllegalStateException("close failed"))
                .when(currentState).closeTenantAndFallback(10L, 20L);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.closeTenant(10L, 20L, close));
        verify(recorder, never()).record(anyLong(), any(TenantActivityDraft.class));
    }

    private TenantActivityDraft capture(TenantActivityRecorder recorder) {
        ArgumentCaptor<TenantActivityDraft> captor = ArgumentCaptor.forClass(TenantActivityDraft.class);
        verify(recorder).record(anyLong(), captor.capture());
        return captor.getValue();
    }

    private static TenantDO teamTenant() {
        TenantDO tenant = new TenantDO();
        tenant.setTenantId(20L);
        tenant.setType("TEAM");
        tenant.setName("Verse 团队");
        return tenant;
    }

    private static UserDO user(Long userId, String username, String nickname) {
        UserDO user = new UserDO();
        user.setUserId(userId);
        user.setUsername(username);
        user.setNickname(nickname);
        return user;
    }
}
