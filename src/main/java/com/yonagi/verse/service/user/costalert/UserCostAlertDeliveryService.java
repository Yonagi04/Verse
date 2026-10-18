package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.yonagi.verse.async.event.NotificationEvent;
import com.yonagi.verse.common.config.*;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import com.yonagi.verse.service.messaging.*;
import com.yonagi.verse.service.notification.NotificationCreationService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** 固定请求ID入队，消息处理中的状态只镜像，外部网络调用全部位于事务外。 */
@Service @Slf4j
public class UserCostAlertDeliveryService {
    private final UserCostAlertDeliveryProperties properties;
    private final UserModelUsageAccessPolicy access;
    private final UserCostAlertConfigMapper configs;
    private final UserCostAlertEventMapper events;
    private final UserCostAlertDeliveryMapper deliveries;
    private final UserCostAlertSendEligibility guard;
    private final UserCostAlertChannelReadinessService readiness;
    private final NotificationCreationService notifications;
    private final SendRecordStore records;
    private final SmsSendService sms;
    private final EmailSendService email;
    private final MessagingProperties messaging;
    private final BudgetPeriodResolver time;
    private final MeterRegistry metrics;
    private final TransactionTemplate tx;
    @Value("${verse.frontend-baseurl}") private String frontendBaseUrl;
    private long scanAfter;
    public UserCostAlertDeliveryService(UserCostAlertDeliveryProperties properties,UserModelUsageAccessPolicy access,UserCostAlertConfigMapper configs,
            UserCostAlertEventMapper events,UserCostAlertDeliveryMapper deliveries,UserCostAlertSendEligibility guard,
            UserCostAlertChannelReadinessService readiness,NotificationCreationService notifications,SendRecordStore records,SmsSendService sms,
            EmailSendService email,MessagingProperties messaging,BudgetPeriodResolver time,MeterRegistry metrics,PlatformTransactionManager manager) {
        this.properties=properties; this.access=access; this.configs=configs; this.events=events; this.deliveries=deliveries; this.guard=guard;
        this.readiness=readiness; this.notifications=notifications; this.records=records; this.sms=sms; this.email=email; this.messaging=messaging;
        this.time=time; this.metrics=metrics; tx=new TransactionTemplate(manager); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    @Scheduled(fixedDelayString="${verse.personal-usage.delivery-poll-ms:2000}")
    public void poll() {
        if (!properties.isDeliveryEnabled()) return;
        var batch=deliveries.selectList(Wrappers.lambdaQuery(UserCostAlertDeliveryDO.class).gt(UserCostAlertDeliveryDO::getDeliveryId,scanAfter)
                .in(UserCostAlertDeliveryDO::getStatus,"PENDING","RETRY_WAIT","CLAIMED","QUEUED")
                .le(UserCostAlertDeliveryDO::getNextRetryAt,time.local(time.now())).orderByAsc(UserCostAlertDeliveryDO::getDeliveryId).last("LIMIT 100"));
        for (var task:batch) {
            try { dispatch(task); }
            catch (RuntimeException failure) { log.error("[personal-alert] 派发待恢复 requestId={}, failureType={}",task.getRequestId(),failure.getClass().getSimpleName()); }
        }
        scanAfter=batch.size()<100 ? 0 : batch.getLast().getDeliveryId();
    }
    public void dispatch(UserCostAlertDeliveryDO lookup) {
        Claim claim=tx.execute(ignored -> claim(lookup));
        if (claim==null) return;
        MessageSubmissionRespDTO outcome;
        try { outcome=enqueue(claim); }
        catch (RuntimeException failure) { outcome=null; }
        var result=outcome;
        tx.executeWithoutResult(ignored -> {
            access.lock(claim.task().getRecipientUserId()); var row=guard.lock(claim.task().getDeliveryId());
            if (row==null || !"CLAIMED".equals(row.getStatus()) || !Objects.equals(row.getExecutionToken(),claim.task().getExecutionToken())) return;
            var submitted=records.findSubmission(MessageChannel.valueOf(row.getChannel()),row.getRequestId());
            if (submitted.isPresent()) {
                var record=submitted.get(); finish(row,List.of("SUBMITTING","QUEUED").contains(record.status()) ? "QUEUED" : record.status(),record.errorCode()); return;
            }
            if (result==null || result.retryable()) {
                boolean expired=row.getAttemptCount()>=3 || !row.getCreateTime().plusSeconds(60).isAfter(time.local(time.now()));
                finish(row,expired ? result==null ? "UNKNOWN" : "EXPIRED" : "RETRY_WAIT",result==null ? "ENQUEUE_UNCERTAIN" : result.errorCode());
                row.setNextRetryAt(time.local(time.now()).plusSeconds(1L<<row.getAttemptCount())); deliveries.updateById(row);
            } else finish(row,result.status().name(),result.errorCode());
        });
    }
    private Claim claim(UserCostAlertDeliveryDO lookup) {
        var user=access.lock(lookup.getRecipientUserId()); var config=configs.selectById(user.getUserId());
        var event=events.selectById(lookup.getEventId()); var task=guard.lock(lookup.getDeliveryId());
        if (task==null || !List.of("PENDING","RETRY_WAIT","CLAIMED","QUEUED").contains(task.getStatus())) return null;
        var now=time.local(time.now());
        if ("CLAIMED".equals(task.getStatus()) && task.getLeaseUntil()!=null && task.getLeaseUntil().isAfter(now)) return null;
        if (!"IN_APP".equals(task.getChannel())) {
            var existing=records.findSubmission(MessageChannel.valueOf(task.getChannel()),task.getRequestId());
            if (existing.isPresent()) {
                var record=existing.get(); finish(task,List.of("SUBMITTING","QUEUED").contains(record.status()) ? "QUEUED" : record.status(),record.errorCode()); return null;
            }
        }
        if ("QUEUED".equals(task.getStatus())) { finish(task,"UNKNOWN","SEND_RECORD_MISSING"); return null; }
        if (task.getCancelRequestedAt()!=null) { finish(task,"CANCELLED","CANCEL_REQUESTED"); return null; }
        String invalid=guard.stateReason(event,config,task.getChannel());
        if (invalid!=null) { finish(task,"CANCELLED",invalid); return null; }
        if (!task.getCreateTime().plusSeconds(60).isAfter(now) || task.getAttemptCount()>=3) { finish(task,"EXPIRED","TASK_EXPIRED"); return null; }
        if ("IN_APP".equals(task.getChannel())) {
            var notification=new NotificationEvent(); notification.setNotificationId(event.getNotificationId()); notification.setTenantId(null);
            notification.setType("SYSTEM"); notification.setSeverity("WARNING"); notification.setTitle(UserCostAlertMessageTemplates.title(event));
            notification.setContent(UserCostAlertMessageTemplates.body(event)+"\n查看或设置："+UserCostAlertMessageTemplates.link(frontendBaseUrl));
            notification.setRecipientUserIds(List.of(user.getUserId())); notifications.createAndPushIfAbsent(notification);
            finish(task,"ACCEPTED",null); return null;
        }
        if (!readiness.readiness(user).stream().anyMatch(row -> row.channel().equals(task.getChannel()) && row.available())) { finish(task,"CANCELLED","CHANNEL_UNAVAILABLE"); return null; }
        String hash=readiness.hash(user,task.getChannel());
        if (hash==null || task.getContactHash()!=null && !task.getContactHash().equals(hash)) { finish(task,"CANCELLED","CONTACT_CHANGED"); return null; }
        task.setContactHash(hash); task.setStatus("CLAIMED"); task.setAttemptCount(task.getAttemptCount()+1);
        task.setExecutionToken(UUID.randomUUID().toString()); task.setLeaseUntil(now.plusSeconds(30)); task.setUpdateTime(now); deliveries.updateById(task);
        return new Claim(task,event,readiness.contact(user,task.getChannel()));
    }
    private MessageSubmissionRespDTO enqueue(Claim claim) {
        var task=claim.task(); var event=claim.event();
        if ("SMS".equals(task.getChannel())) {
            return sms.send(new SmsSendReqDTO(task.getRequestId(),UserCostAlertSendEligibility.SCENE,
                    task.getRecipientUserId(),null,messaging.getSms().getSignName(),claim.contact(),messaging.getSms().getPersonalCostWarningTemplateCode(),UserCostAlertMessageTemplates.sms(event)));
        }
        return email.send(new EmailSendReqDTO(task.getRequestId(),UserCostAlertSendEligibility.SCENE,task.getRecipientUserId(),null,claim.contact(),
                UserCostAlertMessageTemplates.title(event),UserCostAlertMessageTemplates.body(event)+"\n查看或设置："+UserCostAlertMessageTemplates.link(frontendBaseUrl),null));
    }
    private void finish(UserCostAlertDeliveryDO task,String status,String reason) {
        deliveries.update(Wrappers.lambdaUpdate(UserCostAlertDeliveryDO.class).eq(UserCostAlertDeliveryDO::getDeliveryId,task.getDeliveryId())
                .set(UserCostAlertDeliveryDO::getStatus,status).set(UserCostAlertDeliveryDO::getReasonCode,reason)
                .set(UserCostAlertDeliveryDO::getExecutionToken,null).set(UserCostAlertDeliveryDO::getLeaseUntil,null)
                .set(UserCostAlertDeliveryDO::getUpdateTime,time.local(time.now())));
        task.setStatus(status); task.setReasonCode(reason); task.setExecutionToken(null); task.setLeaseUntil(null);
        metrics.counter("personal.alert.delivery.results","channel",task.getChannel(),"status",status).increment();
    }
    private record Claim(UserCostAlertDeliveryDO task,UserCostAlertEventDO event,String contact) { }
}
