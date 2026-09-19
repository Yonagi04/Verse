package com.yonagi.verse.async.outbox;

import com.yonagi.verse.common.config.DomainOutboxProperties;
import com.yonagi.verse.common.enums.DomainEventOutboxStatus;
import com.yonagi.verse.dao.mapper.DomainEventOutboxMapper;
import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.concurrent.atomic.AtomicLong;

/** 通用 Outbox 生产、投递、消费和对账指标。 */
@Component
public class DomainOutboxMetrics {
    private final DomainEventOutboxMapper mapper;
    private final DomainOutboxProperties properties;
    private final Counter staged, stagingFailures, publishFailures, consumeFailures, duplicates;
    private final Timer publishLatency, endToEndLatency;
    private final AtomicLong pending = new AtomicLong(), retry = new AtomicLong(), failed = new AtomicLong();
    private final AtomicLong oldestAge = new AtomicLong(), unreconciled = new AtomicLong();

    public DomainOutboxMetrics(DomainEventOutboxMapper mapper, DomainOutboxProperties properties, MeterRegistry registry) {
        this.mapper=mapper; this.properties=properties;
        staged=registry.counter("verse.domain.outbox.staged"); stagingFailures=registry.counter("verse.domain.outbox.staging.failures");
        publishFailures=registry.counter("verse.domain.outbox.publish.failures"); consumeFailures=registry.counter("verse.tenant.activity.consume.failures");
        duplicates=registry.counter("verse.tenant.activity.duplicates"); publishLatency=registry.timer("verse.domain.outbox.publish.latency");
        endToEndLatency=registry.timer("verse.tenant.activity.end.to.end.latency");
        registry.gauge("verse.domain.outbox.pending",pending); registry.gauge("verse.domain.outbox.retry",retry);
        registry.gauge("verse.domain.outbox.failed",failed); registry.gauge("verse.domain.outbox.oldest.age.seconds",oldestAge);
        registry.gauge("verse.domain.outbox.unreconciled",unreconciled);
    }
    public void staged(){staged.increment();} public void stagingFailed(){stagingFailures.increment();}
    public void publishFailed(){publishFailures.increment();} public void consumeFailed(){consumeFailures.increment();}
    public void duplicate(){duplicates.increment();} public void recordPublishLatency(Duration d){publishLatency.record(d);}
    public void recordEndToEnd(Duration d){endToEndLatency.record(d);}
    public void refresh(){
        pending.set(mapper.countStatus(DomainEventOutboxStatus.PENDING.name())+mapper.countStatus(DomainEventOutboxStatus.CLAIMED.name()));
        retry.set(mapper.countStatus(DomainEventOutboxStatus.RETRY.name())); failed.set(mapper.countStatus(DomainEventOutboxStatus.FAILED.name()));
        LocalDateTime oldest=mapper.oldestUnfinishedAt(); oldestAge.set(oldest==null?0:Math.max(0,Duration.between(oldest,LocalDateTime.now()).toSeconds()));
        unreconciled.set(mapper.countPublishedMissingFacts(LocalDateTime.now().minusNanos(Math.max(0,properties.getReconciliationThresholdMs())*1_000_000)));
    }
}
