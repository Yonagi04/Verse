package com.yonagi.verse.service.messaging;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.convention.exception.MessageSendException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 发送记录也是短期持久化任务；每次状态变更短事务提交，外发不持有行锁。 */
@Component
public class SendRecordStore {
    private final SmsSendRecordMapper sms;
    private final EmailSendRecordMapper emails;
    private final AesUtil aes;
    private final TransactionTemplate transaction;

    public SendRecordStore(SmsSendRecordMapper sms, EmailSendRecordMapper emails, AesUtil aes, PlatformTransactionManager manager) {
        this.sms = sms; this.emails = emails; this.aes = aes;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public MessageSubmissionRespDTO enqueue(SmsSendReqDTO request, String provider, VerificationCodeStore.Reservation verification,
                                            MessagingProperties.Async bounds) {
        try {
            var state = verification == null ? null : new MessageDeliveryTaskDTO.VerificationState(
                    verification.codeKey(), verification.rateKey(), verification.backupKey(), verification.ownerKey());
            var row = new SmsSendRecordDO();
            populate(row, request.requestId(), request.scene(), request.userId(), request.tenantId(), provider,
                    request.phoneNumbers(), JSON.toJSONString(request), new MessageDeliveryTaskDTO(request, null, state), bounds);
            row.setSignName(request.signName()); row.setTemplateCode(request.templateCode());
            return enqueue(row, sms, bounds);
        } catch (MessageSendException failure) { throw failure; }
        catch (RuntimeException failure) { throw new MessageSendException(MessagingErrorCode.RECORD_FAILED, false); }
    }

    public MessageSubmissionRespDTO enqueue(EmailSendReqDTO request, String provider, MessagingProperties.Async bounds) {
        try {
            var row = new EmailSendRecordDO();
            populate(row, request.requestId(), request.scene(), request.userId(), request.tenantId(), provider,
                    request.toAddress(), JSON.toJSONString(request), new MessageDeliveryTaskDTO(null, request, null), bounds);
            row.setSubjectHash(aes.hashForLookup(request.subject()));
            return enqueue(row, emails, bounds);
        } catch (MessageSendException failure) { throw failure; }
        catch (RuntimeException failure) { throw new MessageSendException(MessagingErrorCode.RECORD_FAILED, false); }
    }

    private void populate(MessageSendRecordDO row, String requestId, String scene, Long userId, Long tenantId, String provider,
                          String recipient, String fingerprintSource, MessageDeliveryTaskDTO task, MessagingProperties.Async bounds) {
        row.setRequestId(requestId); row.setScene(scene); row.setUserId(userId); row.setTenantId(tenantId); row.setProvider(provider);
        row.setRecipientEncrypted(aes.encrypt(recipient)); row.setRecipientHash(aes.hashForLookup(recipient));
        row.setRequestFingerprint(aes.hashForLookup(fingerprintSource)); row.setPayloadEncrypted(aes.encrypt(JSON.toJSONString(task)));
        row.setStatus("QUEUED"); row.setAttemptCount(0);
        row.setCreateTime(new Date()); row.setUpdateTime(row.getCreateTime()); row.setNextAttemptAt(row.getCreateTime());
        row.setDeadlineAt(new Date(row.getCreateTime().getTime() + bounds.getMaxTaskAge().toMillis()));
    }

    private <T extends MessageSendRecordDO> MessageSubmissionRespDTO enqueue(T row, BaseMapper<T> mapper, MessagingProperties.Async bounds) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new MessageSendException(MessagingErrorCode.TRANSACTION_ACTIVE, false);
        var inserted = new AtomicBoolean(false);
        try {
            T existing = mapper.selectOne(Wrappers.lambdaQuery(entityType(row)).eq(MessageSendRecordDO::getRequestId, row.getRequestId()));
            if (existing != null) return replay(row, existing);
            transaction.executeWithoutResult(status -> {
                long backlog = mapper.selectCount(Wrappers.lambdaQuery(entityType(row))
                        .in(MessageSendRecordDO::getStatus, "QUEUED", "SUBMITTING").gt(MessageSendRecordDO::getDeadlineAt, new Date()));
                if (backlog >= bounds.getBacklogLimit()) throw new MessageSendException(MessagingErrorCode.BUSY, false);
                if (mapper.insert(row) != 1) throw new IllegalStateException("Task insert failed");
                inserted.set(true);
            });
            return MessageSubmissionRespDTO.queued();
        } catch (DuplicateKeyException duplicate) {
            try {
                T existing = mapper.selectOne(Wrappers.lambdaQuery(entityType(row)).eq(MessageSendRecordDO::getRequestId, row.getRequestId()));
                if (existing == null) throw new IllegalStateException("Duplicate task missing");
                return replay(row, existing);
            } catch (MessageSendException failure) { throw failure; }
            catch (RuntimeException failure) { throw new MessageSendException(MessagingErrorCode.RECORD_FAILED, true); }
        } catch (MessageSendException failure) { throw failure; }
        catch (RuntimeException failure) {
            // 写入成功之后的提交确认可能丢失，任务可能已被后台认领，不能撤销验证码。
            throw new MessageSendException(MessagingErrorCode.RECORD_FAILED, inserted.get());
        }
    }

    private MessageSubmissionRespDTO replay(MessageSendRecordDO requested, MessageSendRecordDO existing) {
        if (!existing.getRequestFingerprint().equals(requested.getRequestFingerprint())) throw new MessageSendException(MessagingErrorCode.REQUEST_CONFLICT, false);
        return result(existing);
    }

    public List<Long> due(MessageChannel channel, int limit) {
        if (limit <= 0) return List.of();
        return channel == MessageChannel.SMS ? due(sms, SmsSendRecordDO.class, limit) : due(emails, EmailSendRecordDO.class, limit);
    }

    public long queuedCount(MessageChannel channel) {
        return channel == MessageChannel.SMS ? queuedCount(sms, SmsSendRecordDO.class) : queuedCount(emails, EmailSendRecordDO.class);
    }
    private <T extends MessageSendRecordDO> long queuedCount(BaseMapper<T> mapper, Class<T> type) {
        return mapper.selectCount(Wrappers.lambdaQuery(type).eq(MessageSendRecordDO::getStatus, "QUEUED"));
    }

    private <T extends MessageSendRecordDO> List<Long> due(BaseMapper<T> mapper, Class<T> type, int limit) {
        Date now = new Date();
        return mapper.selectList(Wrappers.lambdaQuery(type).select(MessageSendRecordDO::getId)
                .eq(MessageSendRecordDO::getStatus, "QUEUED").isNotNull(MessageSendRecordDO::getPayloadEncrypted)
                .and(query -> query.le(MessageSendRecordDO::getNextAttemptAt, now).or().le(MessageSendRecordDO::getDeadlineAt, now))
                .orderByAsc(MessageSendRecordDO::getNextAttemptAt, MessageSendRecordDO::getId).last("LIMIT " + Math.min(limit, 1100)))
                .stream().map(MessageSendRecordDO::getId).toList();
    }

    public Optional<ClaimedTask> claim(MessageChannel channel, long id) {
        return channel == MessageChannel.SMS ? claim(sms, SmsSendRecordDO.class, id) : claim(emails, EmailSendRecordDO.class, id);
    }

    private <T extends MessageSendRecordDO> Optional<ClaimedTask> claim(BaseMapper<T> mapper, Class<T> type, long id) {
        return Objects.requireNonNull(transaction.execute(status -> {
            T row = mapper.selectById(id); Date now = new Date();
            if (row == null || !"QUEUED".equals(row.getStatus()) || row.getPayloadEncrypted() == null
                    || row.getDeadlineAt() == null || row.getNextAttemptAt() == null
                    || (row.getNextAttemptAt().after(now) && row.getDeadlineAt().after(now))) return Optional.<ClaimedTask>empty();
            String token = UUID.randomUUID().toString();
            int changed = mapper.update(null, Wrappers.lambdaUpdate(type).eq(MessageSendRecordDO::getId, id)
                    .eq(MessageSendRecordDO::getStatus, "QUEUED").eq(MessageSendRecordDO::getAttemptCount, row.getAttemptCount())
                    .set(MessageSendRecordDO::getStatus, "SUBMITTING").set(MessageSendRecordDO::getExecutionToken, token)
                    .set(MessageSendRecordDO::getAttemptCount, row.getAttemptCount() + 1).set(MessageSendRecordDO::getUpdateTime, now));
            if (changed != 1) return Optional.<ClaimedTask>empty();
            row.setStatus("SUBMITTING"); row.setExecutionToken(token); row.setAttemptCount(row.getAttemptCount() + 1); row.setUpdateTime(now);
            return Optional.of(new ClaimedTask(row));
        }));
    }

    public MessageDeliveryTaskDTO payload(ClaimedTask reservation) {
        var row = reservation.row();
        var payload = JSON.parseObject(aes.decrypt(row.getPayloadEncrypted()), MessageDeliveryTaskDTO.class);
        Object request = payload.sms() != null ? payload.sms() : payload.email();
        if ((row instanceof SmsSendRecordDO) != (payload.sms() != null)
                || !row.getRequestFingerprint().equals(aes.hashForLookup(JSON.toJSONString(request)))) throw new IllegalStateException("Task fingerprint mismatch");
        return payload;
    }

    public void complete(ClaimedTask reservation, MessageSubmissionRespDTO result, long durationMs) {
        if (result.status() == MessageSubmissionStatus.QUEUED) throw new IllegalArgumentException("Invalid completion");
        transition(reservation, result, durationMs, null);
    }

    public void retry(ClaimedTask reservation, MessageSubmissionRespDTO result, long durationMs, Date nextAttempt) {
        if (!result.retryable() || !nextAttempt.before(reservation.row().getDeadlineAt())) throw new IllegalArgumentException("Unsafe retry");
        transition(reservation, result, durationMs, nextAttempt);
    }

    private void transition(ClaimedTask reservation, MessageSubmissionRespDTO result, long durationMs, Date retryAt) {
        var row = reservation.row();
        if (row instanceof SmsSendRecordDO smsRow) transition(smsRow, sms, result, durationMs, retryAt);
        else if (row instanceof EmailSendRecordDO emailRow) transition(emailRow, emails, result, durationMs, retryAt);
        else throw new IllegalStateException("Unknown record type");
    }

    private <T extends MessageSendRecordDO> void transition(T row, BaseMapper<T> mapper, MessageSubmissionRespDTO result, long durationMs, Date retryAt) {
        transaction.executeWithoutResult(status -> {
            var update = Wrappers.lambdaUpdate(entityType(row)).eq(MessageSendRecordDO::getId, row.getId())
                    .eq(MessageSendRecordDO::getStatus, "SUBMITTING").eq(MessageSendRecordDO::getExecutionToken, row.getExecutionToken())
                    .set(MessageSendRecordDO::getStatus, retryAt == null ? result.status().name() : "QUEUED")
                    .set(MessageSendRecordDO::getProviderRequestId, result.providerRequestId()).set(MessageSendRecordDO::getProviderMessageId, result.providerMessageId())
                    .set(MessageSendRecordDO::getErrorCode, result.errorCode()).set(MessageSendRecordDO::getDurationMs, durationMs)
                    .set(MessageSendRecordDO::getExecutionToken, null).set(MessageSendRecordDO::getUpdateTime, new Date());
            if (retryAt == null) update.set(MessageSendRecordDO::getPayloadEncrypted, null);
            else update.set(MessageSendRecordDO::getNextAttemptAt, retryAt);
            if (mapper.update(null, update) != 1) throw new IllegalStateException("Task transition lost ownership");
        });
    }

    public int recoverAbandoned(MessageChannel channel, Date before) {
        return channel == MessageChannel.SMS ? recoverAbandoned(sms, SmsSendRecordDO.class, before) : recoverAbandoned(emails, EmailSendRecordDO.class, before);
    }

    private <T extends MessageSendRecordDO> int recoverAbandoned(BaseMapper<T> mapper, Class<T> type, Date before) {
        return Objects.requireNonNull(transaction.execute(status -> mapper.update(null, Wrappers.lambdaUpdate(type)
                .eq(MessageSendRecordDO::getStatus, "SUBMITTING").isNotNull(MessageSendRecordDO::getDeadlineAt)
                .le(MessageSendRecordDO::getUpdateTime, before).set(MessageSendRecordDO::getStatus, "UNKNOWN")
                .set(MessageSendRecordDO::getErrorCode, "ABANDONED_EXECUTION").set(MessageSendRecordDO::getPayloadEncrypted, null)
                .set(MessageSendRecordDO::getExecutionToken, null).set(MessageSendRecordDO::getUpdateTime, new Date()))));
    }

    private static MessageSubmissionRespDTO result(MessageSendRecordDO row) {
        var status = MessageSubmissionStatus.valueOf(row.getStatus());
        return status == MessageSubmissionStatus.SUBMITTING ? MessageSubmissionRespDTO.unknown("IN_PROGRESS")
                : new MessageSubmissionRespDTO(status, row.getProviderRequestId(), row.getProviderMessageId(), row.getErrorCode());
    }

    public record ClaimedTask(MessageSendRecordDO row) { }
    @SuppressWarnings("unchecked") private static <T extends MessageSendRecordDO> Class<T> entityType(T row) { return (Class<T>) row.getClass(); }
}
