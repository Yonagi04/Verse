package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import com.yonagi.verse.common.enums.MessageChannel;
import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.service.messaging.SendAttemptContext;
import com.yonagi.verse.service.messaging.SendRecordStore;
import com.yonagi.verse.service.messaging.MessageSendAttemptPolicy;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CostAlertDeliveryGuardTest {
    final LlmCostBudgetLedger llm = mock(LlmCostBudgetLedger.class);
    final TenantCostBudgetLedger tenant = mock(TenantCostBudgetLedger.class);
    final TenantMapper tenants = mock(TenantMapper.class);
    final LlmCostAlertEventMapper events = mock(LlmCostAlertEventMapper.class);
    final LlmCostAlertDeliveryMapper tasks = mock(LlmCostAlertDeliveryMapper.class);
    final UserMapper users = mock(UserMapper.class);
    final SendRecordStore records = mock(SendRecordStore.class);
    final SendAttemptContext attempt = new SendAttemptContext(MessageChannel.EMAIL, 8L, "attempt-1", 1, "hash");
    UserDO user;
    final BudgetPeriodResolver time = new BudgetPeriodResolver(Clock.fixed(Instant.parse("2026-10-17T07:00:00Z"),ZoneOffset.UTC));
    final CostAlertDeliveryGuard guard = new CostAlertDeliveryGuard(llm,tenant,tenants,events,tasks,users,records,time);
    LlmCostAlertEventDO event; LlmCostAlertDeliveryDO task; TenantCostBudgetDO config; CostBudgetPeriodDO period;
    @BeforeEach void setup() {
        var db = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        for (var type : List.of(UserDO.class,LlmCostAlertEventDO.class,LlmCostAlertDeliveryDO.class)) com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(db,"guard"),type);
        event = new LlmCostAlertEventDO(); event.setEventId("e"); event.setTenantId(2L); event.setScopeType("TENANT"); event.setScopeId(2L);
        event.setPeriodType("DAY"); event.setPeriodStart(time.resolve(time.now()).getFirst().start()); event.setPeriodEnd(time.resolve(time.now()).getFirst().end()); event.setEventType("LIMIT");
        task = new LlmCostAlertDeliveryDO(); task.setId(1L); task.setRequestId("r"); task.setEventId("e"); task.setRecipientUserId(1L); task.setChannel("EMAIL"); task.setStatus("QUEUED"); task.setContactHash("hash");
        config = new TenantCostBudgetDO(); config.setTenantId(2L); config.setCostLimitEnabled(true); config.setDailyLimitFen(new BigDecimal("100")); config.setWarningEnabled(true); config.setWarningThresholdPercent(80); config.setWarningChannels("[\"EMAIL\"]");
        period = new CostBudgetPeriodDO(); period.setUsedCostFen(new BigDecimal("100"));
        when(tenant.lock(2L)).thenReturn(config); when(tenant.ready(eq(config),any())).thenReturn(true); when(tenant.load(eq(config),any())).thenReturn(period);
        when(tenant.stagedCosts(eq(2L), any())).thenReturn(CostReconciliationBudgetRead.StagedCosts.EMPTY);
        when(tasks.selectOne(any())).thenReturn(task); when(tasks.lock(1L)).thenReturn(task); when(events.selectOne(any())).thenReturn(event);
        when(records.lockAttempt(anyString(), anyString(), anyLong(), anyLong(), any())).thenReturn(true);
        when(tenants.recipientRole(2L,1L)).thenReturn("ADMIN"); user = new UserDO(); user.setEmailHash("hash"); when(users.selectOne(any())).thenReturn(user);
    }
    @Test void eligibleTenantDeliveryAndModelDeliveryUseOwnScope() {
        assertNull(guard.verify("TENANT_COST_LIMIT","r",attempt)); verify(tenant).lock(2L); verifyNoInteractions(llm);
        var model = new LlmCostBudgetDO(); model.setTenantId(2L); model.setServiceId(10L); model.setCostLimitEnabled(true); model.setWarningEnabled(true); model.setWarningThresholdPercent(80); model.setWarningChannels("[\"EMAIL\"]");
        model.setDailyLimitFen(new BigDecimal("100")); event.setScopeType("LLM"); event.setServiceId(10L);
        when(llm.lock(2L,10L)).thenReturn(model); when(llm.ready(eq(model),any())).thenReturn(true); when(llm.load(eq(model),any())).thenReturn(period);
        when(llm.stagedCosts(eq(2L), any())).thenReturn(CostReconciliationBudgetRead.StagedCosts.EMPTY);
        assertNull(guard.verify("LLM_COST_LIMIT","r",attempt)); verify(llm).lock(2L,10L);
        assertNull(guard.verify("RESET_PASSWORD","r",attempt));
    }

    @Test void confirmedStagedAmountKeepsLimitDeliveryEligibleAndOverflowRetries() {
        period.setUsedCostFen(new BigDecimal("60"));
        period.setCalculatedCount(1L);
        var staged = new CostBudgetSettlementDO();
        staged.setRequestStartedAt(time.local(time.now()));
        staged.setEstimatedCostFen(new BigDecimal("40"));
        when(tenant.stagedCosts(eq(2L), any())).thenReturn(new CostReconciliationBudgetRead.StagedCosts(true, List.of(staged)));
        assertNull(guard.verify("TENANT_COST_LIMIT","r",attempt));
        assertEquals(new BigDecimal("60"), period.getUsedCostFen());

        when(tenant.stagedCosts(eq(2L), any())).thenReturn(CostReconciliationBudgetRead.StagedCosts.UNAVAILABLE);
        assertTrue(guard.verify("TENANT_COST_LIMIT","r",attempt).retryable());
    }
    @ParameterizedTest @CsvSource({"MEMBER,EMAIL,ROLE_CHANGED","NONE,EMAIL,RECIPIENT_LEFT","ADMIN,SMS,CONTACT_CHANGED"})
    void recipientsRecheckedWithoutGrantingMemberFinancialNotifications(String role,String channel,String expected) {
        when(tenants.recipientRole(2L,1L)).thenReturn("NONE".equals(role) ? null : role); task.setChannel(channel);
        var result = guard.verify("TENANT_COST_LIMIT","r",attempt);
        assertEquals(expected,result.errorCode());
    }
    @Test void budgetUnavailableDoesNotCancelOrSendAndChangedStateCancels() {
        when(tenant.ready(eq(config),any())).thenReturn(false); assertTrue(guard.verify("TENANT_COST_LIMIT","r",attempt).retryable());
        when(tenant.ready(eq(config),any())).thenReturn(true); period.setUsedCostFen(BigDecimal.ZERO);
        assertEquals("STATE_CHANGED",guard.verify("TENANT_COST_LIMIT","r",attempt).errorCode());
        config.setCostLimitEnabled(false); assertEquals("BUDGET_DISABLED",guard.verify("TENANT_COST_LIMIT","r",attempt).errorCode());
        event.setPeriodEnd(time.local(time.now())); assertEquals("PERIOD_EXPIRED",guard.verify("TENANT_COST_LIMIT","r",attempt).errorCode());
    }
    @Test void removedChannelChangedContactAndCancelledTaskCannotSend() {
        event.setEventType("WARNING"); period.setUsedCostFen(new BigDecimal("80")); config.setWarningChannels("[\"IN_APP\"]");
        assertEquals("CHANNEL_REMOVED",guard.verify("TENANT_COST_WARNING","r",attempt).errorCode());
        config.setWarningChannels("[\"EMAIL\"]"); when(users.selectOne(any())).thenReturn(null);
        assertEquals("CONTACT_CHANGED",guard.verify("TENANT_COST_WARNING","r",attempt).errorCode());
        task.setStatus("CANCELLED"); assertEquals("DELIVERY_CANCELLED",guard.verify("TENANT_COST_WARNING","r",attempt).errorCode());
    }
    @Test void missingTaskOrEventIsExplicitCancellation() {
        when(tasks.selectOne(any())).thenReturn(null); assertEquals("DELIVERY_MISSING",guard.verify("TENANT_COST_LIMIT","r",attempt).errorCode());
        when(tasks.selectOne(any())).thenReturn(task); when(events.selectOne(any())).thenReturn(null); assertEquals("EVENT_MISSING",guard.verify("TENANT_COST_LIMIT","r",attempt).errorCode());
    }

    @ParameterizedTest
    @CsvSource({
            "TENANT,WARNING,SMS", "TENANT,WARNING,EMAIL", "TENANT,LIMIT,SMS", "TENANT,LIMIT,EMAIL",
            "LLM,WARNING,SMS", "LLM,WARNING,EMAIL", "LLM,LIMIT,SMS", "LLM,LIMIT,EMAIL"
    })
    void copiedStaleUserHashCannotAuthorizeADifferentActualRecipient(String scope, String type, String channel) {
        configureAlert(scope, type, channel);
        task.setContactHash("stale-user-hash");
        user.setPhoneHash("stale-user-hash");
        user.setEmailHash("stale-user-hash");
        SendAttemptContext actualRecipient = new SendAttemptContext(
                MessageChannel.valueOf(channel), 8L, "attempt-1", 1, "actual-address-hash");

        MessageSubmissionRespDTO result = guard.verify(scope + "_COST_" + type, "r", actualRecipient);

        assertCancelled("CONTACT_CHANGED", result);
        verifyNoInteractions(records);
    }

    @ParameterizedTest
    @CsvSource({
            "TENANT,WARNING,SMS", "TENANT,WARNING,EMAIL", "TENANT,LIMIT,SMS", "TENANT,LIMIT,EMAIL",
            "LLM,WARNING,SMS", "LLM,WARNING,EMAIL", "LLM,LIMIT,SMS", "LLM,LIMIT,EMAIL"
    })
    void matchingActualRecipientRequiresTheCurrentAttemptInTheEventTenant(String scope, String type, String channel) {
        configureAlert(scope, type, channel);
        String scene = scope + "_COST_" + type;
        SendAttemptContext current = new SendAttemptContext(MessageChannel.valueOf(channel), 8L, "attempt-1", 1, "hash");

        assertNull(guard.verify(scene, "r", current));
        verify(records).lockAttempt(scene, "r", 1L, 2L, current);
        verify(records, never()).lockAttempt(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void oldEntryCannotAuthorizeAnyCostSceneAndOtherScenesAreIgnored() {
        for (String scene : List.of("TENANT_COST_WARNING", "TENANT_COST_LIMIT", "LLM_COST_WARNING", "LLM_COST_LIMIT")) {
            assertCancelled("ATTEMPT_CONTEXT_REQUIRED", guard.verify(scene, "r"));
        }
        assertNull(guard.verify("PASSWORD_RESET", "r"));
        assertNull(guard.verify(null, "r"));
        assertNull(guard.verify(null, "r", attempt));
        assertNull(guard.verify("COST_RECONCILIATION", "r", attempt));
        verifyNoInteractions(records, tasks, events, tenant, llm);
    }

    @ParameterizedTest
    @MethodSource("incompleteAttempts")
    void missingAttemptIdentityCannotAuthorizeSending(SendAttemptContext incomplete) {
        assertCancelled("ATTEMPT_CONTEXT_REQUIRED", guard.verify("TENANT_COST_LIMIT", "r", incomplete));
        verifyNoInteractions(records, tasks, events, tenant, llm);
    }

    static Stream<SendAttemptContext> incompleteAttempts() {
        return Stream.of(null,
                new SendAttemptContext(null, 8L, "token", 1, "hash"),
                new SendAttemptContext(MessageChannel.EMAIL, null, "token", 1, "hash"),
                new SendAttemptContext(MessageChannel.EMAIL, 8L, null, 1, "hash"),
                new SendAttemptContext(MessageChannel.EMAIL, 8L, " ", 1, "hash"),
                new SendAttemptContext(MessageChannel.EMAIL, 8L, "token", null, "hash"),
                new SendAttemptContext(MessageChannel.EMAIL, 8L, "token", 0, "hash"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingOrBlankHashesNeverAuthorizeSending(String hash) {
        user.setEmailHash(hash);
        task.setContactHash(hash);
        SendAttemptContext sameMissingHash = new SendAttemptContext(MessageChannel.EMAIL, 8L, "token", 1, hash);
        assertCancelled("CONTACT_CHANGED", guard.verify("TENANT_COST_LIMIT", "r", sameMissingHash));
        verifyNoInteractions(records);
    }

    @Test
    void wrongChannelSceneAndCorrectedUserWithStaleTaskRemainRejected() {
        SendAttemptContext wrongChannel = new SendAttemptContext(MessageChannel.SMS, 8L, "attempt-1", 1, "hash");
        assertCancelled("CONTACT_CHANGED", guard.verify("TENANT_COST_LIMIT", "r", wrongChannel));
        assertCancelled("SEND_ATTEMPT_CHANGED", guard.verify("LLM_COST_LIMIT", "r", attempt));
        assertCancelled("SEND_ATTEMPT_CHANGED", guard.verify("TENANT_COST_WARNING", "r", attempt));
        user.setEmailHash("corrected-hash");
        assertCancelled("CONTACT_CHANGED", guard.verify("TENANT_COST_LIMIT", "r", attempt));
        verifyNoInteractions(records);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCEPTED", "UNKNOWN", "REJECTED", "CANCELLED", "EXPIRED"})
    void terminalTasksCannotAcquireAnotherSendPermit(String status) {
        task.setStatus(status);
        assertCancelled("DELIVERY_CANCELLED", guard.verify("TENANT_COST_LIMIT", "r", attempt));
        verifyNoInteractions(records);
    }

    @Test
    void missingLockedTaskAndMissingRecipientHashCannotAuthorizeSending() {
        when(tasks.lock(1L)).thenReturn(null);
        assertCancelled("DELIVERY_CANCELLED", guard.verify("TENANT_COST_LIMIT", "r", attempt));
        when(tasks.lock(1L)).thenReturn(task);
        SendAttemptContext noHash = new SendAttemptContext(MessageChannel.EMAIL, 8L, "attempt-1", 1, null);
        assertCancelled("CONTACT_CHANGED", guard.verify("TENANT_COST_LIMIT", "r", noHash));
        verifyNoInteractions(records);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "user", "scene", "request", "token", "attempt", "hash", "state", "deadline"})
    void staleOrForeignMessageRecordsCannotAuthorizeEvenWithMatchingContact(String changed) {
        EmailSendRecordDO message = new EmailSendRecordDO();
        message.setStatus("SUBMITTING"); message.setTenantId(2L); message.setUserId(1L);
        message.setScene("TENANT_COST_LIMIT"); message.setRequestId("r");
        message.setExecutionToken("attempt-1"); message.setAttemptCount(1); message.setRecipientHash("hash");
        Date permitTime = new Date(1_000_000L);
        message.setDeadlineAt(new Date(permitTime.getTime() + 1_000));
        switch (changed) {
            case "tenant" -> message.setTenantId(99L);
            case "user" -> message.setUserId(99L);
            case "scene" -> message.setScene("PASSWORD_RESET");
            case "request" -> message.setRequestId("other-request");
            case "token" -> message.setExecutionToken("stale-token");
            case "attempt" -> message.setAttemptCount(2);
            case "hash" -> message.setRecipientHash("other-address");
            case "state" -> message.setStatus("ACCEPTED");
            case "deadline" -> message.setDeadlineAt(permitTime);
            default -> throw new IllegalArgumentException("Unknown fixture: " + changed);
        }
        when(records.lockAttempt(anyString(), anyString(), anyLong(), anyLong(), any())).thenAnswer(call ->
                MessageSendAttemptPolicy.matches(message, call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), call.getArgument(3), call.getArgument(4), permitTime));

        assertCancelled("SEND_ATTEMPT_CHANGED", guard.verify("TENANT_COST_LIMIT", "r", attempt));
    }

    @Test
    void attemptDependencyFailureIsPropagatedForTheWorkersBoundedRetry() {
        when(records.lockAttempt(anyString(), anyString(), anyLong(), anyLong(), any()))
                .thenThrow(new IllegalStateException("dependency unavailable"));
        assertThrows(IllegalStateException.class, () -> guard.verify("TENANT_COST_LIMIT", "r", attempt));
    }

    @Test
    void legacyModelScopeAndInAppMemberEligibilityRemainCompatible() {
        configureAlert("LLM", "LIMIT", "EMAIL");
        event.setScopeType(null);
        assertNull(guard.verify("LLM_COST_LIMIT", "r", attempt));
        task.setChannel("IN_APP");
        when(tenants.recipientRole(2L, 1L)).thenReturn("MEMBER");
        assertNull(guard.recipientReason(event, task, guard.lockState(event)));
    }

    private void assertCancelled(String reason, MessageSubmissionRespDTO result) {
        assertNotNull(result, "A denied attempt must have an explicit cancellation result");
        assertEquals(MessageSubmissionStatus.CANCELLED, result.status());
        assertEquals(reason, result.errorCode());
    }

    private void configureAlert(String scope, String type, String channel) {
        event.setScopeType(scope);
        event.setEventType(type);
        task.setChannel(channel);
        user.setPhoneHash("hash");
        user.setEmailHash("hash");
        config.setWarningChannels("[\"SMS\",\"EMAIL\"]");
        period.setUsedCostFen(new BigDecimal("WARNING".equals(type) ? "80" : "100"));
        if ("LLM".equals(scope)) {
            event.setServiceId(10L);
            LlmCostBudgetDO model = new LlmCostBudgetDO();
            model.setTenantId(2L);
            model.setServiceId(10L);
            model.setCostLimitEnabled(true);
            model.setWarningEnabled(true);
            model.setWarningThresholdPercent(80);
            model.setWarningChannels("[\"SMS\",\"EMAIL\"]");
            model.setDailyLimitFen(new BigDecimal("100"));
            when(llm.lock(2L, 10L)).thenReturn(model);
            when(llm.ready(eq(model), any())).thenReturn(true);
            when(llm.stagedCosts(eq(2L), any())).thenReturn(CostReconciliationBudgetRead.StagedCosts.EMPTY);
            when(llm.load(eq(model), any())).thenReturn(period);
        }
    }
}
