package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.DomainOutboxProperties;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

/** 对账事实并仅清理已发布、已对账且超过保留期的 Outbox。 */
@Slf4j @Component @RequiredArgsConstructor
public class DomainOutboxMaintenanceJob {
    private final DomainEventOutboxMapper mapper; private final DomainOutboxProperties properties; private final DomainOutboxMetrics metrics;
    @Scheduled(fixedDelayString="${verse.async.domain-outbox.maintenance-delay-ms:60000}")
    public void reconcileAndCleanup(){
        LocalDateTime now=LocalDateTime.now(); int reconciled=mapper.reconcilePublished(now);
        int deleted=mapper.deleteReconciledBefore(now.minusDays(Math.max(1,properties.getReconciledRetentionDays()))); metrics.refresh();
        if(reconciled>0||deleted>0) log.info("[domain-outbox] 对账清理完成: reconciled={}, deleted={}",reconciled,deleted);
    }
}
