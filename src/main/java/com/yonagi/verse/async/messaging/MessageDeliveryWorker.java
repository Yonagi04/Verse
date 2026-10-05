package com.yonagi.verse.async.messaging;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.convention.exception.MessageSendException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.messaging.provider.*;
import com.yonagi.verse.dto.req.MessageDeliveryTaskDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.messaging.*;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** 后台用例协调：认领和落库各自短事务，服务商调用位于事务之外。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageDeliveryWorker {
    private final SendRecordStore records;
    private final SmsProvider sms;
    private final EmailProvider email;
    private final VerificationCodeStore codes;
    private final MessagingProperties properties;
    private final MeterRegistry metrics;

    public void execute(MessageChannel channel, long id) {
        records.claim(channel, id).ifPresent(reservation -> executeClaimed(channel, reservation));
    }

    private void executeClaimed(MessageChannel channel, SendRecordStore.ClaimedTask reservation) {
        long started = System.nanoTime();
        VerificationCodeStore.Reservation verification = null;
        MessageSubmissionRespDTO result;
        boolean invoking = false;
        try {
            MessageDeliveryTaskDTO payload = records.payload(reservation);
            verification = verification(payload);
            if (!reservation.row().getDeadlineAt().after(new Date())) result = MessageSubmissionRespDTO.expired("TASK_EXPIRED");
            else if (reservation.row().getAttemptCount() > properties.getAsync().getMaxAttempts()) result = MessageSubmissionRespDTO.rejected("ATTEMPTS_EXHAUSTED");
            else if (!reservation.row().getProvider().equals(channel == MessageChannel.SMS ? sms.name() : email.name())) result = MessageSubmissionRespDTO.rejected("PROVIDER_CHANGED");
            else if (verification != null && !codes.isCurrent(verification)) result = MessageSubmissionRespDTO.expired("STALE_VERIFICATION");
            else {
                if (channel == MessageChannel.SMS) sms.validate(payload.sms()); else email.validate(payload.email());
                // Redis 预检也可能耗时，外发前再次确认任务仍在有效期内。
                if (!reservation.row().getDeadlineAt().after(new Date())) result = MessageSubmissionRespDTO.expired("TASK_EXPIRED");
                else {
                    invoking = true;
                    result = channel == MessageChannel.SMS ? sms.send(payload.sms()) : email.send(payload.email());
                    if (result == null || result.status() == MessageSubmissionStatus.QUEUED || result.status() == MessageSubmissionStatus.EXPIRED) {
                        result = MessageSubmissionRespDTO.unknown("INVALID_ADAPTER_RESULT");
                    }
                }
            }
        } catch (MessageSendException failure) {
            result = !invoking && MessagingErrorCode.STATE_FAILED.code().equals(failure.getErrorCode())
                    ? MessageSubmissionRespDTO.retryable("STATE_FAILED")
                    : (invoking ? MessageSubmissionRespDTO.unknown("ADAPTER_ERROR") : MessageSubmissionRespDTO.rejected("TASK_INVALID"));
        } catch (RuntimeException failure) {
            result = invoking ? MessageSubmissionRespDTO.unknown("ADAPTER_ERROR") : MessageSubmissionRespDTO.rejected("TASK_INVALID");
        }
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        String label = channel.name().toLowerCase(java.util.Locale.ROOT);
        metrics.counter("verse.messaging.submissions", "channel", label, "provider", reservation.row().getProvider(), "status", result.status().name()).increment();
        metrics.timer("verse.messaging.duration", "channel", label, "provider", reservation.row().getProvider()).record(elapsed, TimeUnit.MILLISECONDS);
        try {
            Date retryAt = retryAt(reservation, result);
            if (retryAt != null) {
                records.retry(reservation, result, elapsed, retryAt);
                metrics.counter("verse.messaging.retries", "channel", label).increment();
                return;
            }
            records.complete(reservation, result, elapsed);
        } catch (RuntimeException persistenceFailure) {
            metrics.counter("verse.messaging.record.failures", "channel", label).increment();
            log.error("[messaging] 异步结果落库失败: channel={}, recordId={}, outcome={}", channel, reservation.row().getId(), result.status());
            // 留下 SUBMITTING，后续只转 UNKNOWN；不能根据落库失败重复发送。
            // 若尝试写入重试状态，提交确认丢失也可能实际已排队；此时保留验证码。
            if (result.retryable() && reservation.row().getAttemptCount() < properties.getAsync().getMaxAttempts()) return;
        }
        if (verification != null && (result.status() == MessageSubmissionStatus.REJECTED || result.status() == MessageSubmissionStatus.EXPIRED)) {
            try { codes.restore(verification); }
            catch (RuntimeException restoreFailure) {
                metrics.counter("verse.messaging.verification.restore.failures", "channel", label).increment();
                log.error("[messaging] 验证码恢复失败: recordId={}", reservation.row().getId());
            }
        }
    }

    private VerificationCodeStore.Reservation verification(MessageDeliveryTaskDTO payload) {
        if (payload.verification() == null) return null;
        var state = payload.verification(); var request = payload.sms();
        return new VerificationCodeStore.Reservation(state.codeKey(), state.rateKey(), state.backupKey(), state.ownerKey(),
                request.templateParams().get("code"), request.requestId());
    }

    private Date retryAt(SendRecordStore.ClaimedTask reservation, MessageSubmissionRespDTO result) {
        int attempt = reservation.row().getAttemptCount();
        if (!result.retryable() || attempt >= properties.getAsync().getMaxAttempts()) return null;
        long base = properties.getAsync().getRetryBackoff().toMillis();
        long delay = base * (1L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(base / 2 + 1);
        Date next = new Date(System.currentTimeMillis() + delay);
        return next.before(reservation.row().getDeadlineAt()) ? next : null;
    }
}
