package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.common.enums.UsageOutboxStatus;
import com.yonagi.verse.dao.entity.TokenUsageOutboxDO;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 将已持久化的计费用量事件可靠转发到 RocketMQ。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageOutboxRelay {
    private final UsageOutboxClaimService claimService;
    private final TokenUsageOutboxMapper outboxMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final UsageOutboxProperties properties;
    private final UsageOutboxMetrics metrics;
    private final String owner = UUID.randomUUID().toString();

    @Value("${rocketmq.producer.topic}")
    private String topic;
    @Value("${rocketmq.producer.send-message-timeout:3000}")
    private long sendTimeout;

    @Scheduled(fixedDelayString = "${verse.llm.usage-outbox.relay-delay-ms:1000}")
    public void relay() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            List<TokenUsageOutboxDO> rows = claimService.claim(owner);
            rows.forEach(this::sendOne);
            metrics.refreshBacklog();
        } catch (RuntimeException e) {
            log.error("[usage-outbox] Relay 批次失败: owner={}", owner, e);
        }
    }

    private void sendOne(TokenUsageOutboxDO row) {
        Instant startedAt = Instant.now();
        try {
            String destination = topic + ":" + row.getEventType();
            Message<String> message = MessageBuilder.withPayload(row.getPayloadJson())
                    .setHeader(MessageConst.PROPERTY_KEYS, row.getEventId())
                    .build();
            SendResult result = rocketMQTemplate.syncSendOrderly(
                    destination, message, row.getMessageKey(), sendTimeout);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new IllegalStateException("RocketMQ 未返回 SEND_OK: "
                        + (result == null ? "null" : result.getSendStatus()));
            }
            int updated = outboxMapper.markPublished(row.getId(), owner, LocalDateTime.now());
            if (updated != 1) {
                throw new IllegalStateException("发送成功但 Outbox 状态更新失败，等待租约恢复重发");
            }
            Instant stagedAt = row.getCreateTime() == null
                    ? startedAt : row.getCreateTime().atZone(java.time.ZoneId.systemDefault()).toInstant();
            metrics.recordPublishLatency(Duration.between(stagedAt, Instant.now()));
            log.info("[usage-outbox] 发布成功: eventId={}, msgId={}, attempt={}",
                    row.getEventId(), result.getMsgId(), row.getAttemptCount() + 1);
        } catch (RuntimeException e) {
            recordFailure(row, e);
        }
    }

    private void recordFailure(TokenUsageOutboxDO row, RuntimeException error) {
        int attempt = row.getAttemptCount() + 1;
        boolean exhausted = attempt >= Math.max(1, properties.getMaxAttempts());
        String status = exhausted ? UsageOutboxStatus.FAILED.name() : UsageOutboxStatus.RETRY.name();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime retryAt = exhausted ? now : now.plusNanos(backoffMillis(attempt) * 1_000_000);
        String errorText = abbreviate(error.getClass().getSimpleName() + ": " + error.getMessage(), 2000);
        outboxMapper.markPublishFailure(row.getId(), owner, status, retryAt, errorText, now);
        metrics.publishFailed();
        log.error("[usage-outbox] 发布失败: eventId={}, attempt={}, status={}, nextRetryAt={}",
                row.getEventId(), attempt, status, retryAt, error);
    }

    private long backoffMillis(int attempt) {
        long initial = Math.max(1, properties.getInitialBackoffMs());
        long maximum = Math.max(initial, properties.getMaxBackoffMs());
        int shift = Math.min(30, Math.max(0, attempt - 1));
        long candidate;
        try {
            candidate = Math.multiplyExact(initial, 1L << shift);
        } catch (ArithmeticException ignored) {
            candidate = maximum;
        }
        return Math.min(maximum, candidate);
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
