package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.ReliableDomainEventPublisher;
import com.yonagi.verse.async.event.CostReconciliationEvent;
import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.MessageChannel;
import com.yonagi.verse.common.messaging.MessageRequestValidation;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.CostReconciliationBatchDO;
import com.yonagi.verse.dao.mapper.CostReconciliationBatchMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.LlmCostAlertDeliveryMapper;
import com.yonagi.verse.dao.mapper.LlmCostBudgetMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.EmailSendReqDTO;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.messaging.EmailSendService;
import com.yonagi.verse.service.messaging.MessageSendEligibility;
import com.yonagi.verse.service.messaging.SendAttemptContext;
import com.yonagi.verse.service.messaging.SendRecordStore;
import com.yonagi.verse.service.messaging.SmsSendService;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class CostReconciliationNotifications implements MessageSendEligibility {
    private static final String SCENE = "COST_RECONCILIATION";
    private final CostReconciliationBatchMapper batches;
    private final CostReconciliationCaseMapper cases;
    private final LlmCostAlertDeliveryMapper recipients;
    private final LlmCostBudgetMapper roles;
    private final UserMapper users;
    private final TenantAccessPolicy tenants;
    private final ReliableDomainEventPublisher events;
    private final SendRecordStore records;
    private final SmsSendService sms;
    private final EmailSendService email;
    private final AesUtil aes;
    private final MessagingProperties messaging;
    private final BudgetPeriodResolver time;
    private final TransactionTemplate transaction;

    @Value("${verse.llm.cost-reconciliation.notifications-enabled:false}")
    private boolean enabled;
    @Value("${verse.llm.cost-reconciliation.frontend-origin:http://reconciliation.localhost:3000}")
    private String origin;
    @Value("${verse.llm.cost-reconciliation.sms-template:10008}")
    private String smsTemplate;

    public CostReconciliationNotifications(CostReconciliationBatchMapper batches, CostReconciliationCaseMapper cases,
                                           LlmCostAlertDeliveryMapper recipients, LlmCostBudgetMapper roles, UserMapper users,
                                           TenantAccessPolicy tenants, ReliableDomainEventPublisher events, SendRecordStore records,
                                           SmsSendService sms, EmailSendService email, AesUtil aes, MessagingProperties messaging,
                                           BudgetPeriodResolver time, PlatformTransactionManager manager) {
        this.batches = batches;
        this.cases = cases;
        this.recipients = recipients;
        this.roles = roles;
        this.users = users;
        this.tenants = tenants;
        this.events = events;
        this.records = records;
        this.sms = sms;
        this.email = email;
        this.aes = aes;
        this.messaging = messaging;
        this.time = time;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public void stageReadyBatches() {
        if (!enabled) {
            return;
        }
        for (var candidate : batches.awaitingNotification()) {
            transaction.executeWithoutResult(status -> stage(candidate.getId()));
        }
    }

    private void stage(Long batchId) {
        var row = batches.lock(batchId);
        if (row == null || row.getNotifiedAt() != null) {
            return;
        }
        long pending = cases.count(batchId, "PENDING");
        if (pending > 0) {
            var event = new CostReconciliationEvent();
            event.setEventId("cost-reconciliation-notice-" + batchId);
            event.setKey(String.valueOf(row.getTenantId()));
            event.setBatchId(batchId);
            events.publish(event, row.getTenantId());
        }
        row.setNotifiedAt(time.local(time.now()));
        batches.updateById(row);
    }

    public void enqueue(Long batchId) {
        if (!enabled) {
            throw new IllegalStateException("reconciliation notifications paused");
        }
        var batch = transaction.execute(status -> snapshot(batchId));
        if (batch == null || batch.getRecipientsJson() == null) {
            return;
        }
        var plan = JSON.parseObject(batch.getRecipientsJson(), Plan.class);
        RuntimeException retry = null;
        for (Group group : plan.groups()) {
            try {
                if (records.findSubmission(group.channel(), group.requestId()).isEmpty()) {
                    send(batch, plan, group);
                }
            } catch (RuntimeException failure) {
                // 一个收件组排队失败时，仍让其他联系方式正常提交。重放时沿用原幂等身份。
                if (retry == null) {
                    retry = failure;
                }
            }
        }
        if (retry != null) {
            throw retry;
        }
    }

    private CostReconciliationBatchDO snapshot(Long batchId) {
        var row = batches.lock(batchId);
        if (row == null || cases.count(batchId, "PENDING") == 0) {
            return null;
        }
        if (row.getRecipientsJson() != null) {
            return row;
        }
        String tenantName;
        try {
            tenantName = tenants.activeTenant(row.getTenantId()).getName();
        } catch (ClientException closed) {
            return null;
        }
        var groups = new LinkedHashMap<String, Group>();
        long after = 0;
        List<com.yonagi.verse.dao.projection.CostAlertRecipient> page;
        do {
            page = recipients.reconciliationAdmins(row.getTenantId(), after);
            for (var recipient : page) {
                add(groups, row, MessageChannel.EMAIL, recipient.getEmail(), recipient.getUserId());
                add(groups, row, MessageChannel.SMS, recipient.getPhone(), recipient.getUserId());
                after = recipient.getUserId();
            }
        } while (page.size() == 200);
        if (groups.isEmpty()) {
            log.warn("[reconciliation] 批次没有可达的管理员 batchId={}", batchId);
        }
        row.setRecipientsJson(JSON.toJSONString(new Plan(tenantName, cases.count(batchId, "PENDING"),
                List.copyOf(groups.values()))));
        batches.updateById(row);
        return row;
    }

    private void add(Map<String, Group> groups, CostReconciliationBatchDO batch, MessageChannel channel,
                     String encrypted, Long userId) {
        if (encrypted == null || encrypted.isBlank()) {
            return;
        }
        String address = aes.decrypt(encrypted);
        if (channel == MessageChannel.SMS ? !address.matches("1[3-9][0-9]{9}") : !MessageRequestValidation.email(address)) {
            log.warn("[reconciliation] 管理员联系方式不可用 batchId={} userId={} channel={}", batch.getId(), userId, channel);
            return;
        }
        String hash = aes.hashForLookup(address);
        String requestId = "rec-" + batch.getId() + "-" + channel.name() + "-" + hash.substring(0, 24);
        var group = groups.computeIfAbsent(requestId, ignored -> new Group(requestId, channel, encrypted, hash, new ArrayList<>()));
        group.userIds().add(userId);
    }

    private void send(CostReconciliationBatchDO batch, Plan plan, Group group) {
        Long userId = group.userIds().getFirst();
        String address = aes.decrypt(group.encrypted());
        if (group.channel() == MessageChannel.SMS) {
            if ("disabled".equals(messaging.getSms().getProvider())) {
                log.warn("[reconciliation] 短信通道未配置 batchId={}", batch.getId());
                return;
            }
            sms.send(new SmsSendReqDTO(group.requestId(), SCENE, userId, batch.getTenantId(), messaging.getSms().getSignName(),
                    address, smsTemplate, Map.of("batchCode", batch.getCode())));
        } else {
            if ("disabled".equals(messaging.getEmail().getProvider())) {
                log.warn("[reconciliation] 邮件通道未配置 batchId={}", batch.getId());
                return;
            }
            String name = plan.tenantName().replaceAll("[\\r\\n]", " ");
            String body = "管理员您好！\n\nVerse 在恢复后的核对中发现，租户「" + name + "」本批次有 "
                    + plan.pending() + " 笔模型调用费用仍待核对。请打开自助补账页面并登录：\n"
                    + link(batch.getCode()) + "\n\n页面仅展示本次扫描的待补账和已处理记录。"
                    + "待补账期间，模型调用按已确认费用判断限额。";
            email.send(new EmailSendReqDTO(group.requestId(), SCENE, userId, batch.getTenantId(), address,
                    "【Verse】" + name + "有模型用量待补账", body, null));
        }
    }

    private String link(String code) {
        var url = URI.create(origin);
        if (!List.of("http", "https").contains(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null
                || url.getQuery() != null || url.getFragment() != null
                || url.getPath() != null && !url.getPath().isEmpty() && !"/".equals(url.getPath())) {
            throw new IllegalStateException("invalid reconciliation origin");
        }
        return url.getScheme() + "://" + url.getRawAuthority() + "/" + code;
    }

    @Override
    public MessageSubmissionRespDTO verify(String scene, String requestId) {
        return SCENE.equals(scene) ? MessageSubmissionRespDTO.rejected("MISSING_SEND_PERMIT") : null;
    }

    @Override
    public MessageSubmissionRespDTO verify(String scene, String requestId, SendAttemptContext attempt) {
        if (!SCENE.equals(scene)) {
            return null;
        }
        if (!enabled) {
            return MessageSubmissionRespDTO.retryable("RECONCILIATION_PAUSED");
        }
        if (requestId == null || !requestId.matches("rec-[0-9]{1,18}-(EMAIL|SMS)-[a-f0-9]{24}")) {
            return MessageSubmissionRespDTO.rejected("INVALID_RECONCILIATION_NOTICE");
        }
        Long batchId = Long.parseLong(requestId.split("-")[1]);
        return transaction.execute(status -> permit(batchId, requestId, attempt));
    }

    private MessageSubmissionRespDTO permit(Long batchId, String requestId, SendAttemptContext attempt) {
        var batch = batches.selectById(batchId);
        if (batch == null || batch.getRecipientsJson() == null) {
            return MessageSubmissionRespDTO.rejected("BATCH_UNAVAILABLE");
        }
        try {
            tenants.lockActiveTenantForShare(batch.getTenantId());
        } catch (ClientException closed) {
            return MessageSubmissionRespDTO.rejected("BATCH_UNAVAILABLE");
        }
        var group = JSON.parseObject(batch.getRecipientsJson(), Plan.class).groups().stream()
                .filter(row -> row.requestId().equals(requestId) && row.channel() == attempt.channel()
                        && Objects.equals(row.hash(), attempt.recipientHash())).findFirst().orElse(null);
        if (group == null || !stillEligible(batch, group) || cases.lockPending(batchId) == null
                || !records.lockAttempt(SCENE, requestId, group.userIds().getFirst(), attempt)) {
            return MessageSubmissionRespDTO.rejected("RECONCILIATION_NOTICE_STALE");
        }
        return null;
    }

    private boolean stillEligible(CostReconciliationBatchDO batch, Group group) {
        for (Long id : group.userIds()) {
            var user = users.selectActiveUserForShare(id);
            if (user == null) {
                continue;
            }
            String current = group.channel() == MessageChannel.SMS ? user.getPhoneHash() : user.getEmailHash();
            String role = roles.role(batch.getTenantId(), id);
            if (Objects.equals(current, group.hash()) && ("ADMIN".equals(role) || "SUPER_ADMIN".equals(role))) {
                return true;
            }
        }
        return false;
    }

    public record Plan(String tenantName, long pending, List<Group> groups) {}
    public record Group(String requestId, MessageChannel channel, String encrypted, String hash, List<Long> userIds) {}
}
