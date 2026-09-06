package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.UsageOutboxProperties;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 仅清理已发布且已与事实表对账的 Outbox 记录。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageOutboxCleanupJob {
    private final TokenUsageOutboxMapper outboxMapper;
    private final UsageOutboxProperties properties;

    @Scheduled(fixedDelayString = "${verse.llm.usage-outbox.cleanup-delay-ms:3600000}")
    public void reconcileAndCleanup() {
        LocalDateTime now = LocalDateTime.now();
        int reconciled = outboxMapper.reconcilePublished(now);
        int deleted = outboxMapper.deleteReconciledBefore(now.minusDays(Math.max(1, properties.getRetentionDays())));
        if (reconciled > 0 || deleted > 0) {
            log.info("[usage-outbox] 对账清理完成: reconciled={}, deleted={}", reconciled, deleted);
        }
    }
}
