package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.MessageChannel;
import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.common.messaging.EmailTemplateRenderer;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.projection.CostAlertRecipient;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.LlmCostAlertDeliveryMapper;
import com.yonagi.verse.dao.mapper.LlmCostBudgetMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.EmailSendReqDTO;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;

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
    private final LlmCostAlertDeliveryMapper recipients = mock(LlmCostAlertDeliveryMapper.class);
    private final TenantAccessPolicy tenants = mock(TenantAccessPolicy.class);
    private final SmsSendService sms = mock(SmsSendService.class);
    private final MessagingProperties properties = new MessagingProperties();
    private CostReconciliationNotifications service;
    private CostReconciliationBatchDO batch;
    private UserDO user;

    @BeforeEach
    void setup() {
        properties.getEmail().setProvider("mailpit");
        service = new CostReconciliationNotifications(batches, cases, recipients, roles,
                users, tenants, events, records,
                sms, email, new EmailTemplateRenderer(
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

    @ParameterizedTest
    @ValueSource(strings = {"https://user@example.com", "ftp://example.com", "https://example.com/path",
            "https://example.com/?query=value", "https://example.com/#fragment", "http:/missing-host"})
    void invalidOriginCannotEnqueueMail(String origin) {
        snapshot(MessageChannel.EMAIL);
        when(aes.decrypt("encrypted-contact")).thenReturn("admin@example.com");
        ReflectionTestUtils.setField(service, "origin", origin);
        assertThrows(IllegalStateException.class, () -> service.enqueue(1L));
        verify(email, never()).send(any());
    }

    @Test
    void firstSnapshotPagesAdministratorsAndDeduplicatesTheSharedContact() {
        var tenant = new TenantDO();
        tenant.setName("team\r\nname");
        when(tenants.activeTenant(TENANT_ID)).thenReturn(tenant);
        when(recipients.reconciliationAdmins(TENANT_ID, 0L)).thenReturn(
                LongStream.rangeClosed(1, 200).mapToObj(id -> recipient(id, "encrypted-email", null)).toList());
        when(recipients.reconciliationAdmins(TENANT_ID, 200L))
                .thenReturn(List.of(recipient(201L, "encrypted-email", null)));
        when(aes.decrypt("encrypted-email")).thenReturn("admin@example.com");
        when(aes.hashForLookup("admin@example.com")).thenReturn(HASH);

        service.enqueue(1L);

        var plan = JSON.parseObject(batch.getRecipientsJson(), CostReconciliationNotifications.Plan.class);
        assertEquals(1, plan.groups().size());
        assertEquals(201, plan.groups().getFirst().userIds().size());
        assertEquals(201L, plan.groups().getFirst().userIds().getLast());
        var sent = ArgumentCaptor.forClass(EmailSendReqDTO.class);
        verify(email).send(sent.capture());
        assertEquals(TENANT_ID, sent.getValue().tenantId());
        assertEquals(1L, sent.getValue().userId());
        assertEquals("admin@example.com", sent.getValue().toAddress());
        assertEquals("【Verse】team  name有模型用量待补账", sent.getValue().subject());
        verify(recipients).reconciliationAdmins(TENANT_ID, 200L);
        verify(batches).updateById(batch);
    }

    @Test
    void invalidAndMissingContactsCreateAnEmptyStablePlanWithoutSending() {
        var tenant = new TenantDO();
        tenant.setName("test tenant");
        when(tenants.activeTenant(TENANT_ID)).thenReturn(tenant);
        when(recipients.reconciliationAdmins(TENANT_ID, 0L)).thenReturn(List.of(
                recipient(1L, null, " "), recipient(2L, "invalid-email", "invalid-phone")));
        when(aes.decrypt(anyString())).thenAnswer(call -> call.getArgument(0));

        service.enqueue(1L);

        var plan = JSON.parseObject(batch.getRecipientsJson(), CostReconciliationNotifications.Plan.class);
        assertEquals(2L, plan.pending());
        assertEquals(List.of(), plan.groups());
        verify(email, never()).send(any());
        verify(sms, never()).send(any());
        service.enqueue(1L);
        verify(recipients).reconciliationAdmins(TENANT_ID, 0L);
    }

    @Test
    void oneFailedGroupDoesNotStopOtherRecipientsAndRetryKeepsOriginalIdentity() {
        String firstId = "rec-1-EMAIL-" + HASH.substring(0, 24);
        String secondHash = "b".repeat(64);
        String secondId = "rec-1-EMAIL-" + secondHash.substring(0, 24);
        batch.setRecipientsJson(JSON.toJSONString(new CostReconciliationNotifications.Plan("test", 2L, List.of(
                new CostReconciliationNotifications.Group(firstId, MessageChannel.EMAIL, "first", HASH,
                        List.of(USER_ID)),
                new CostReconciliationNotifications.Group(secondId, MessageChannel.EMAIL, "second", secondHash,
                        List.of(43L))))));
        when(aes.decrypt("first")).thenReturn("first@example.com");
        when(aes.decrypt("second")).thenReturn("second@example.com");
        var failure = new IllegalStateException("first group queue unavailable");
        doThrow(failure).when(email).send(argThat(request -> "first@example.com".equals(request.toAddress())));

        assertEquals(failure, assertThrows(IllegalStateException.class, () -> service.enqueue(1L)));
        var sent = ArgumentCaptor.forClass(EmailSendReqDTO.class);
        verify(email, times(2)).send(sent.capture());
        assertEquals(List.of(firstId, secondId), sent.getAllValues().stream().map(EmailSendReqDTO::requestId).toList());
        String originalPlan = batch.getRecipientsJson();
        when(records.findSubmission(MessageChannel.EMAIL, secondId)).thenReturn(Optional.of(
                new SendRecordStore.SubmissionSnapshot("QUEUED", null, secondHash, null)));
        clearInvocations(email);

        assertEquals(failure, assertThrows(IllegalStateException.class, () -> service.enqueue(1L)));
        var retried = ArgumentCaptor.forClass(EmailSendReqDTO.class);
        verify(email).send(retried.capture());
        assertEquals(firstId, retried.getValue().requestId());
        assertEquals(originalPlan, batch.getRecipientsJson());
        verify(recipients, never()).reconciliationAdmins(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void smsUsesOriginalTenantAndBatchAndDisabledChannelDoesNotSend() {
        var tenant = new TenantDO();
        tenant.setName("test tenant");
        when(tenants.activeTenant(TENANT_ID)).thenReturn(tenant);
        when(recipients.reconciliationAdmins(TENANT_ID, 0L))
                .thenReturn(List.of(recipient(USER_ID, null, "encrypted-contact")));
        when(aes.decrypt("encrypted-contact")).thenReturn("13800138000");
        when(aes.hashForLookup("13800138000")).thenReturn(HASH);
        properties.getSms().setProvider("local");
        properties.getSms().setSignName("Verse");
        ReflectionTestUtils.setField(service, "smsTemplate", "cost_reconciliation");
        service.enqueue(1L);
        var sent = ArgumentCaptor.forClass(SmsSendReqDTO.class);
        verify(sms).send(sent.capture());
        assertEquals(TENANT_ID, sent.getValue().tenantId());
        assertEquals(USER_ID, sent.getValue().userId());
        assertEquals("13800138000", sent.getValue().phoneNumbers());
        assertEquals("AbCd1234", sent.getValue().templateParams().get("batchCode"));
        properties.getSms().setProvider("disabled");
        service.enqueue(1L);
        verify(sms).send(any());
    }

    @Test
    void closedTenantMissingBatchAndNoPendingCasesCannotStartNotification() {
        doThrow(new ClientException("tenant closed")).when(tenants).activeTenant(TENANT_ID);
        service.enqueue(1L);
        assertNull(batch.getRecipientsJson());
        when(batches.lock(1L)).thenReturn(null);
        service.enqueue(1L);
        when(batches.lock(1L)).thenReturn(batch);
        when(cases.count(1L, "PENDING")).thenReturn(0L);
        service.enqueue(1L);
        assertNull(batch.getRecipientsJson());
        verify(email, never()).send(any());
        verify(sms, never()).send(any());
    }

    @Test
    void missingPermitMalformedNoticeAndPauseHaveDistinctBusinessErrors() {
        assertEquals("MISSING_SEND_PERMIT", service.verify(SCENE, "unused").errorCode());
        assertNull(service.verify("OTHER", "unused"));
        assertNull(service.verify("OTHER", "unused", attempt(MessageChannel.EMAIL)));
        assertEquals("INVALID_RECONCILIATION_NOTICE",
                service.verify(SCENE, "wrong", attempt(MessageChannel.EMAIL)).errorCode());
        assertEquals("INVALID_RECONCILIATION_NOTICE",
                service.verify(SCENE, null, attempt(MessageChannel.EMAIL)).errorCode());
        String requestId = snapshot(MessageChannel.EMAIL);
        ReflectionTestUtils.setField(service, "enabled", false);
        var result = service.verify(SCENE, requestId, attempt(MessageChannel.EMAIL));
        assertEquals("RECONCILIATION_PAUSED", result.errorCode());
        assertEquals(true, result.retryable());
        assertThrows(IllegalStateException.class, () -> service.enqueue(1L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-batch", "missing-plan", "closed-tenant", "inactive-user", "wrong-channel"})
    void unavailableBatchAndStaleRecipientCannotObtainSendPermit(String reason) {
        String requestId = snapshot(MessageChannel.EMAIL);
        switch (reason) {
            case "missing-batch" -> when(batches.selectById(1L)).thenReturn(null);
            case "missing-plan" -> batch.setRecipientsJson(null);
            case "closed-tenant" -> doThrow(new ClientException("tenant closed"))
                    .when(tenants).lockActiveTenantForShare(TENANT_ID);
            case "inactive-user" -> when(users.selectActiveUserForShare(USER_ID)).thenReturn(null);
            case "wrong-channel" -> { }
            default -> throw new IllegalArgumentException("unknown scenario");
        }
        var result = service.verify(SCENE, requestId,
                attempt("wrong-channel".equals(reason) ? MessageChannel.SMS : MessageChannel.EMAIL));
        assertEquals(reason.startsWith("missing") || "closed-tenant".equals(reason)
                ? "BATCH_UNAVAILABLE" : "RECONCILIATION_NOTICE_STALE", result.errorCode());
        assertEquals(MessageSubmissionStatus.REJECTED, result.status());
        verify(records, never()).lockAttempt(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void remainingAdministratorKeepsSharedContactEligibleAfterAnotherMemberLeaves() {
        String requestId = "rec-1-EMAIL-" + HASH.substring(0, 24);
        batch.setRecipientsJson(JSON.toJSONString(new CostReconciliationNotifications.Plan("test", 2L,
                List.of(new CostReconciliationNotifications.Group(requestId, MessageChannel.EMAIL,
                        "encrypted-contact", HASH, List.of(41L, USER_ID))))));
        when(users.selectActiveUserForShare(41L)).thenReturn(null);
        var attempt = attempt(MessageChannel.EMAIL);
        when(records.lockAttempt(SCENE, requestId, 41L, TENANT_ID, attempt)).thenReturn(true);
        assertNull(service.verify(SCENE, requestId, attempt));
        verify(users).selectActiveUserForShare(USER_ID);
        assertEquals(HASH, user.getEmailHash());
    }

    @Test
    void disabledEmailChannelKeepsSnapshotWithoutSubmittingMail() {
        snapshot(MessageChannel.EMAIL);
        when(aes.decrypt("encrypted-contact")).thenReturn("admin@example.com");
        properties.getEmail().setProvider("disabled");
        String plan = batch.getRecipientsJson();
        service.enqueue(1L);
        assertEquals(plan, batch.getRecipientsJson());
        verify(email, never()).send(any());
    }

    private CostAlertRecipient recipient(long id, String emailAddress, String phone) {
        var recipient = new CostAlertRecipient();
        recipient.setUserId(id);
        recipient.setEmail(emailAddress);
        recipient.setPhone(phone);
        return recipient;
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
