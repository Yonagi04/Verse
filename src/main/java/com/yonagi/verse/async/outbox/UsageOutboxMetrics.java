package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.enums.UsageOutboxStatus;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/** Outbox 可观测指标，避免故障只停留在日志中。 */
@Component
public class UsageOutboxMetrics {
    private final TokenUsageOutboxMapper outboxMapper;
    private final Counter stagedCounter;
    private final Counter stagingFailureCounter;
    private final Counter publishFailureCounter;
    private final Timer publishLatency;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong retry = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong oldestPendingAgeSeconds = new AtomicLong();

    public UsageOutboxMetrics(TokenUsageOutboxMapper outboxMapper, MeterRegistry registry) {
        this.outboxMapper = outboxMapper;
        this.stagedCounter = registry.counter("verse.usage.outbox.staged");
        this.stagingFailureCounter = registry.counter("verse.usage.outbox.staging.failures");
        this.publishFailureCounter = registry.counter("verse.usage.outbox.publish.failures");
        this.publishLatency = registry.timer("verse.usage.outbox.publish.latency");
        registry.gauge("verse.usage.outbox.pending", pending);
        registry.gauge("verse.usage.outbox.retry", retry);
        registry.gauge("verse.usage.outbox.failed", failed);
        registry.gauge("verse.usage.outbox.oldest.pending.age.seconds", oldestPendingAgeSeconds);
    }

    public void staged() {
        stagedCounter.increment();
    }

    public void stagingFailed() {
        stagingFailureCounter.increment();
    }

    public void publishFailed() {
        publishFailureCounter.increment();
    }

    public void recordPublishLatency(Duration duration) {
        publishLatency.record(duration);
    }

    public void refreshBacklog() {
        pending.set(outboxMapper.countStatus(UsageOutboxStatus.PENDING.name())
                + outboxMapper.countStatus(UsageOutboxStatus.CLAIMED.name()));
        retry.set(outboxMapper.countStatus(UsageOutboxStatus.RETRY.name()));
        failed.set(outboxMapper.countStatus(UsageOutboxStatus.FAILED.name()));
        LocalDateTime oldest = outboxMapper.oldestUnfinishedAt();
        oldestPendingAgeSeconds.set(oldest == null ? 0
                : Math.max(0, Duration.between(oldest, LocalDateTime.now()).toSeconds()));
    }
}
