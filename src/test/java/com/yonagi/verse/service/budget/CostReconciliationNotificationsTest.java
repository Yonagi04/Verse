package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.enums.MessageChannel;
import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.common.messaging.EmailTemplateRenderer;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.LlmCostAlertDeliveryMapper;
import com.yonagi.verse.dao.mapper.LlmCostBudgetMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.EmailSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.messaging.EmailSendService;
import com.yonagi.verse.service.messaging.SendAttemptContext;
import com.yonagi.verse.service.messaging.SendRecordStore;
import com.yonagi.verse.service.messaging.SmsSendService;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证发送资格和渲染后的实际请求，数据库锁与投递本身不在单元测试中模拟证明。 */
class CostReconciliationNotificationsTest {
    private static final String SCENE = "COST_RECONCILIATION";
    private static final Long TENANT_ID = 7L;
    private static final Long USER_ID = 42L;
    private static final String HASH = "a".repeat(64);
    private final CostReconciliationBatchMapper batches = mock(CostReconciliationBatchMapper.class);
    private final CostReconciliationCaseMapper cases = mock(CostReconciliationCaseMapper.class);
    private final LlmCostBudgetMapper roles = mock(LlmCostBudgetMapper.class);
    private final UserMapper users = mock(UserMapper.class);
    private final SendRecordStore records = mock(SendRecordStore.class);
    private final EmailSendService email = mock(EmailSendService.class);
    private final AesUtil aes = mock(AesUtil.class);
    private final ReliableDomainEventPublisher events = mock(ReliableDomainEventPublisher.class);
    private CostReconciliationNotifications service;
    private CostReconciliationBatchDO batch;
    private UserDO user;

    @BeforeEach
    void setup() {
        MessagingProperties properties = new MessagingProperties();
        properties.getEmail().setProvider("mailpit");
        service = new CostReconciliationNotifications(batches, cases, mock(LlmCostAlertDeliveryMapper.class), roles,
                users, mock(TenantAccessPolicy.class), events, records,
                mock(SmsSendService.class), email, new EmailTemplateRenderer(
                        new ClassPathResource("messaging/email/templates.yml")), aes, properties,
                new BudgetPeriodResolver(), mock(PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "origin", "http://reconciliation.localhost:3000");
        batch = new CostReconciliationBatchDO();
        batch.setId(1L);
        batch.setTenantId(TENANT_ID);
        batch.setCode("AbCd1234");
        when(batches.selectById(1L)).thenReturn(batch);
        when(batches.lock(1L)).thenReturn(batch);
        when(cases.count(1L, "PENDING")).thenReturn(2L);
        when(cases.lockPending(1L)).thenReturn(100L);
        user = new UserDO();
        user.setUserId(USER_ID);
        user.setPhoneHash(HASH);
        user.setEmailHash(HASH);
        when(users.selectActiveUserForShare(USER_ID)).thenReturn(user);
        when(roles.role(TENANT_ID, USER_ID)).thenReturn("SUPER_ADMIN");
    }

    @Test
    void specifiedBatchWaitsForSealAndAutoCompletionAndPersistsOnlyOneNotice() {
        assertEquals(true, service.stageBatchIfReady(1L));
        assertNull(batch.getNotifiedAt());
        when(batches.ready(1L)).thenReturn(true);
        service.stageBatchIfReady(1L);
        assertEquals(true, batch.getNotifiedAt() != null);
        service.stageBatchIfReady(1L);
        var event = ArgumentCaptor.forClass(com.yonagi.verse.async.event.CostReconciliationEvent.class);
        verify(events).publish(event.capture(), org.mockito.ArgumentMatchers.eq(TENANT_ID));
        assertEquals(1L, event.getValue().getBatchId());
        assertEquals("cost-reconciliation-notice-1",event.getValue().getEventId());
    }

    @Test
    void notificationPauseLeavesOriginalBatchUnstagedForReliableRecovery() {
        ReflectionTestUtils.setField(service,"enabled",false);
        assertEquals(false,service.stageBatchIfReady(1L));
        assertNull(batch.getNotifiedAt());
        verify(events,never()).publish(any(),any());
    }

    @ParameterizedTest
    @EnumSource(MessageChannel.class)
    void permitsOnlyTheCurrentAttemptInTheBatchTenant(MessageChannel channel) {
        String requestId = snapshot(channel);
        SendAttemptContext attempt = attempt(channel);
        when(records.lockAttempt(SCENE, requestId, USER_ID, TENANT_ID, attempt)).thenReturn(true);
        assertNull(service.verify(SCENE, requestId, attempt));
        when(records.lockAttempt(SCENE, requestId, USER_ID, TENANT_ID, attempt)).thenReturn(false);
        assertRejected(service.verify(SCENE, requestId, attempt));
        verify(records, never()).lockAttempt(SCENE, requestId, USER_ID, attempt);
    }

    @ParameterizedTest
    @EnumSource(MessageChannel.class)
    void contactChangesAndClosedCasesRemainRejected(MessageChannel channel) {
        String requestId = snapshot(channel);
        SendAttemptContext attempt = attempt(channel);
        user.setEmailHash("changed");
        user.setPhoneHash("changed");
        assertRejected(service.verify(SCENE, requestId, attempt));
        user.setEmailHash(HASH);
        user.setPhoneHash(HASH);
        when(cases.lockPending(1L)).thenReturn(null);
        assertRejected(service.verify(SCENE, requestId, attempt));
        verify(records, never()).lockAttempt(SCENE, requestId, USER_ID, TENANT_ID, attempt);
    }

    @Test
    void removedAdministratorAndMismatchedRecipientAreRejected() {
        String requestId = snapshot(MessageChannel.EMAIL);
        when(roles.role(TENANT_ID, USER_ID)).thenReturn("MEMBER");
        assertRejected(service.verify(SCENE, requestId, attempt(MessageChannel.EMAIL)));
        when(roles.role(TENANT_ID, USER_ID)).thenReturn("ADMIN");
        assertRejected(service.verify(SCENE, requestId,
                new SendAttemptContext(MessageChannel.EMAIL, 9L, "current-token", 1, "other-hash")));
    }

    @Test
    void enqueueUsesExternalTemplateAndPreservesTenantAndRecipient() {
        String requestId = snapshot(MessageChannel.EMAIL);
        when(aes.decrypt("encrypted-contact")).thenReturn("admin@example.com");
        service.enqueue(1L);
        ArgumentCaptor<EmailSendReqDTO> sent = ArgumentCaptor.forClass(EmailSendReqDTO.class);
        verify(email).send(sent.capture());
        EmailSendReqDTO request = sent.getValue();
        assertEquals(requestId, request.requestId());
        assertEquals(TENANT_ID, request.tenantId());
        assertEquals(USER_ID, request.userId());
        assertEquals("admin@example.com", request.toAddress());
        assertEquals("【Verse】测试 租户有模型用量待补账", request.subject());
        assertEquals("管理员您好！\n\nVerse 在恢复后的核对中发现，租户「测试 租户」本批次有 "
                + "2 笔模型调用费用仍待核对。请打开自助补账页面并登录：\n"
                + "http://reconciliation.localhost:3000/AbCd1234\n\n页面仅展示本次扫描的待补账和已处理记录。"
                + "待补账期间，模型调用按已确认费用判断限额。", request.textBody());
        assertNull(request.htmlBody());
    }

    @Test
    void invalidOriginCannotEnqueueMail() {
        snapshot(MessageChannel.EMAIL);
        when(aes.decrypt("encrypted-contact")).thenReturn("admin@example.com");
        ReflectionTestUtils.setField(service, "origin", "https://user@example.com");
        assertThrows(IllegalStateException.class, () -> service.enqueue(1L));
        verify(email, never()).send(any());
    }

    private String snapshot(MessageChannel channel) {
        String requestId = "rec-1-" + channel + "-" + HASH.substring(0, 24);
        batch.setRecipientsJson(JSON.toJSONString(new CostReconciliationNotifications.Plan("测试\n租户", 2L,
                List.of(new CostReconciliationNotifications.Group(requestId, channel,
                        "encrypted-contact", HASH, List.of(USER_ID))))));
        return requestId;
    }

    private SendAttemptContext attempt(MessageChannel channel) {
        return new SendAttemptContext(channel, 9L, "current-token", 1, HASH);
    }

    private void assertRejected(MessageSubmissionRespDTO result) {
        assertEquals(MessageSubmissionStatus.REJECTED, result.status());
        assertEquals("RECONCILIATION_NOTICE_STALE", result.errorCode());
    }
}
