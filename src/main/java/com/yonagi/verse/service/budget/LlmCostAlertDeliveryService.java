package com.yonagi.verse.service.budget;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.async.activity.TenantActivityRecorder;
import com.yonagi.verse.async.event.NotificationEvent;
import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.messaging.*;
import com.yonagi.verse.service.notification.NotificationCreationService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 本地幂等认领、提交后派发、发送结果镜像；不在事务内调用短信或邮件。 */
@Service
@lombok.extern.slf4j.Slf4j
public class LlmCostAlertDeliveryService {
    private final LlmCostAlertEventMapper events;
    private final LlmCostAlertDeliveryMapper deliveries;
    private final LlmCostBudgetLedger ledger;
    private final CostAlertDeliveryGuard guard;
    private final NotificationCreationService notifications;
    private final TenantActivityRecorder activities;
    private final SmsSendService sms;
    private final EmailSendService email;
    private final SmsSendRecordMapper smsRecords;
    private final EmailSendRecordMapper emailRecords;
    private final MessagingProperties properties;
    private final AesUtil aes;
    private final BudgetPeriodResolver time;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    /** 本实例轮询游标；进程重启可从头重扫，派发仍由数据库锁和固定请求 ID 保证幂等。 */
    private long scanAfterId;
    /** 本轮固定上界，避免持续入队导致旧任务一直无法重访。 */
    private long scanThroughId;
    public LlmCostAlertDeliveryService(LlmCostAlertEventMapper events, LlmCostAlertDeliveryMapper deliveries,
            LlmCostBudgetLedger ledger, CostAlertDeliveryGuard guard, NotificationCreationService notifications,
            TenantActivityRecorder activities, SmsSendService sms, EmailSendService email,
            SmsSendRecordMapper smsRecords, EmailSendRecordMapper emailRecords, MessagingProperties properties,
            AesUtil aes, BudgetPeriodResolver time, MeterRegistry metrics, PlatformTransactionManager manager) {
        this.events=events; this.deliveries=deliveries; this.ledger=ledger; this.guard=guard; this.notifications=notifications;
        this.activities=activities; this.sms=sms; this.email=email; this.smsRecords=smsRecords; this.emailRecords=emailRecords;
        this.properties=properties; this.aes=aes; this.time=time; this.metrics=metrics; this.tx=new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelayString="${verse.llm.cost-alert.poll-millis:2000}")
    public synchronized void scan() {
        if (scanThroughId == 0) scanThroughId = deliveries.maxId();
        if (scanThroughId == 0) return;
        var rows = deliveries.pending(time.local(time.now()), scanAfterId, scanThroughId);
        for (var row : rows) {
            // 派发异常也要前进；该任务会在下一轮重试，不能占住当前批次。
            scanAfterId = row.getId();
            try { dispatch(row); }
            catch (RuntimeException failure) {
                metrics.counter("verse.cost.alert.delivery.failures").increment();
                log.warn("[cost-alert] 派发待恢复: eventId={}, requestId={}, channel={}, failureType={}",
                        row.getEventId(),row.getRequestId(),row.getChannel(),failure.getClass().getSimpleName());
            }
        }
        if (rows.isEmpty() || scanAfterId >= scanThroughId) {
            scanAfterId = 0;
            scanThroughId = 0;
        }
    }
    public void wake(String eventId) {
        var rows=deliveries.selectList(Wrappers.lambdaQuery(LlmCostAlertDeliveryDO.class).eq(LlmCostAlertDeliveryDO::getEventId,eventId)
                .in(LlmCostAlertDeliveryDO::getStatus,"PENDING","RETRY_WAIT","QUEUED").orderByAsc(LlmCostAlertDeliveryDO::getId).last("LIMIT 100"));
        rows.forEach(this::dispatch);
    }
    private void dispatch(LlmCostAlertDeliveryDO lookup) {
        var event=events.selectOne(Wrappers.lambdaQuery(LlmCostAlertEventDO.class).eq(LlmCostAlertEventDO::getEventId,lookup.getEventId()));
        if (event==null) throw new IllegalStateException("alert event missing");
        var task=tx.execute(ignored -> {
            var config=ledger.lock(event.getTenantId(),event.getServiceId());
            events.lock(event.getEventId());
            var row=deliveries.lock(lookup.getId());
            if (row==null || !List.of("PENDING","RETRY_WAIT","CLAIMED","QUEUED").contains(row.getStatus())) return null;
            var now=time.local(time.now());
            if ("CLAIMED".equals(row.getStatus()) && row.getLeaseUntil().isAfter(now)) return null;
            if ("RETRY_WAIT".equals(row.getStatus()) && row.getNextRetryAt().isAfter(now)) return null;
            if ("ACTIVITY".equals(row.getChannel())) {
                activities.recordSystemSnapshot(event); finish(row,"ACCEPTED",null); return null;
            }
            // 已被发送队列接管时只镜像，不能因当前状态变化覆盖实际受理结果。
            var existing=sendRecord(row);
            if (existing!=null) {
                finish(row, Set.of("SUBMITTING","QUEUED").contains(existing.getStatus()) ? "QUEUED" : existing.getStatus(),existing.getErrorCode());
                return null;
            }
            if ("QUEUED".equals(row.getStatus())) { finish(row,"UNKNOWN","SEND_RECORD_MISSING"); return null; }
            String invalid=guard.stateReason(event,config);
            if (invalid!=null) { finish(row,"CANCELLED",invalid); return null; }
            if (!ledger.ready(config,time.now())) { waitOrExpire(row,"BUDGET_UNAVAILABLE"); return null; }
            if ("IN_APP".equals(row.getChannel())) { createAnnouncement(event,config); return null; }
            invalid=guard.recipientReason(event,row);
            if (invalid!=null) { finish(row,"CANCELLED",invalid); return null; }
            if (!row.getCreateTime().plus(properties.getAsync().getMaxTaskAge()).isAfter(now)) { finish(row,"EXPIRED","TASK_EXPIRED"); return null; }
            row.setStatus("CLAIMED"); row.setExecutionToken(UUID.randomUUID().toString()); row.setLeaseUntil(now.plusSeconds(30));
            row.setAttemptCount(row.getAttemptCount()+1); row.setUpdateTime(now); deliveries.updateById(row); return row;
        });
        if (task==null) return;
        MessageSubmissionRespDTO result;
        try { result=enqueue(event,task); }
        catch (RuntimeException failure) {
            // 固定 requestId 恢复，即使提交确认丢失也不会换 ID 重发。
            log.warn("[cost-alert] 入队待恢复: requestId={}, failureType={}", task.getRequestId(), failure.getClass().getSimpleName());
            tx.executeWithoutResult(ignored -> updateClaim(task,null,"ENQUEUE_FAILED")); return;
        }
        var outcome=result;
        tx.executeWithoutResult(ignored -> updateClaim(task,outcome,null));
    }
    private MessageSendRecordDO sendRecord(LlmCostAlertDeliveryDO task) {
        if ("SMS".equals(task.getChannel())) return smsRecords.selectOne(Wrappers.lambdaQuery(SmsSendRecordDO.class).eq(SmsSendRecordDO::getRequestId,task.getRequestId()));
        if ("EMAIL".equals(task.getChannel())) return emailRecords.selectOne(Wrappers.lambdaQuery(EmailSendRecordDO.class).eq(EmailSendRecordDO::getRequestId,task.getRequestId()));
        return null;
    }
    private void createAnnouncement(LlmCostAlertEventDO event,LlmCostBudgetDO config) {
        var candidates=deliveries.selectList(Wrappers.lambdaQuery(LlmCostAlertDeliveryDO.class).eq(LlmCostAlertDeliveryDO::getEventId,event.getEventId())
                .eq(LlmCostAlertDeliveryDO::getChannel,"IN_APP").in(LlmCostAlertDeliveryDO::getStatus,"PENDING","RETRY_WAIT","CLAIMED").last("FOR UPDATE"));
        List<Long> recipients=new ArrayList<>();
        for (var row:candidates) {
            String invalid=guard.recipientReason(event,row);
            if (invalid==null) recipients.add(row.getRecipientUserId()); else finish(row,"CANCELLED",invalid);
        }
        if (recipients.isEmpty()) return;
        String retryAt=ledger.decision(config).retryAt();
        NotificationEvent notification=new NotificationEvent(); notification.setNotificationId(event.getNotificationId()); notification.setTenantId(event.getTenantId());
        notification.setType("SYSTEM"); notification.setSeverity("LIMIT".equals(event.getEventType()) ? "CRITICAL" : "WARNING");
        notification.setTitle(LlmCostAlertTemplates.title(event)); notification.setContent(LlmCostAlertTemplates.publicBody(event,retryAt));
        notification.setRecipientUserIds(recipients); notifications.createAndPushIfAbsent(notification);
        for (var row:candidates) if (recipients.contains(row.getRecipientUserId())) finish(row,"ACCEPTED",null);
    }
    private MessageSubmissionRespDTO enqueue(LlmCostAlertEventDO event,LlmCostAlertDeliveryDO task) {
        boolean warning="WARNING".equals(event.getEventType()); String scene=warning ? "LLM_COST_WARNING" : "LLM_COST_LIMIT";
        String contact=aes.decrypt(task.getContactEncrypted());
        if ("SMS".equals(task.getChannel())) {
            if ("disabled".equals(properties.getSms().getProvider())) return MessageSubmissionRespDTO.rejected("CHANNEL_DISABLED");
            String template=warning ? properties.getSms().getCostWarningTemplateCode() : properties.getSms().getCostLimitTemplateCode();
            if (template==null || template.isBlank()) return MessageSubmissionRespDTO.rejected("TEMPLATE_MISSING");
            return sms.send(new SmsSendReqDTO(task.getRequestId(),scene,task.getRecipientUserId(),event.getTenantId(),
                    properties.getSms().getSignName(),contact,template,LlmCostAlertTemplates.sms(event)));
        }
        if ("disabled".equals(properties.getEmail().getProvider())) return MessageSubmissionRespDTO.rejected("CHANNEL_DISABLED");
        return email.send(new EmailSendReqDTO(task.getRequestId(),scene,task.getRecipientUserId(),event.getTenantId(),contact,
                "【"+LlmCostAlertTemplates.safe(properties.getEmail().getFromAlias())+"】"+LlmCostAlertTemplates.safe(event.getTenantName())+" "+LlmCostAlertTemplates.title(event),
                LlmCostAlertTemplates.emailBody(event),null));
    }
    private void updateClaim(LlmCostAlertDeliveryDO task,MessageSubmissionRespDTO result,String reason) {
        var row=deliveries.lock(task.getId());
        if (row==null || !"CLAIMED".equals(row.getStatus()) || !task.getExecutionToken().equals(row.getExecutionToken())) return;
        if (result==null) waitOrExpire(row,reason); else if (result.retryable()) waitOrExpire(row,result.errorCode()); else finish(row,result.status().name(),result.errorCode());
    }
    private void waitOrExpire(LlmCostAlertDeliveryDO row,String reason) {
        if (row.getAttemptCount()>=properties.getAsync().getMaxAttempts()
                || !row.getCreateTime().plus(properties.getAsync().getMaxTaskAge()).isAfter(time.local(time.now()))) finish(row,"EXPIRED",reason);
        else { row.setNextRetryAt(time.local(time.now()).plus(properties.getAsync().getRetryBackoff())); finish(row,"RETRY_WAIT",reason); }
    }
    private void finish(LlmCostAlertDeliveryDO row,String state,String reason) {
        row.setStatus(state); row.setReasonCode(reason); row.setExecutionToken(null); row.setLeaseUntil(null); row.setUpdateTime(time.local(time.now()));
        deliveries.update(Wrappers.lambdaUpdate(LlmCostAlertDeliveryDO.class).eq(LlmCostAlertDeliveryDO::getId,row.getId())
                .set(LlmCostAlertDeliveryDO::getStatus,state).set(LlmCostAlertDeliveryDO::getReasonCode,reason)
                .set(LlmCostAlertDeliveryDO::getExecutionToken,null).set(LlmCostAlertDeliveryDO::getLeaseUntil,null)
                .set(LlmCostAlertDeliveryDO::getNextRetryAt,row.getNextRetryAt()).set(LlmCostAlertDeliveryDO::getUpdateTime,row.getUpdateTime()));
        metrics.counter("verse.cost.alert.delivery.results","channel",row.getChannel(),"status",state).increment();
        if (reason!=null) metrics.counter("verse.cost.alert.delivery.reasons","channel",row.getChannel(),"reason",reason).increment();
    }
}
