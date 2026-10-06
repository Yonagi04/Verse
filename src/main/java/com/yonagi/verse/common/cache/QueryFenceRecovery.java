package com.yonagi.verse.common.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 只恢复有结束证明的写入；无法确认的栅栏交给停写核验流程。 */
@Slf4j
@Component
public class QueryFenceRecovery {
    private final QueryCache cache;
    private final QueryCatalogue catalogue;
    private final QueryCacheProperties properties;
    private final MeterRegistry metrics;
    private final Set<Completion> pending = new LinkedHashSet<>();
    private final Map<String, Sample> samples = new ConcurrentHashMap<>();

    public QueryFenceRecovery(QueryCache cache, QueryCatalogue catalogue, QueryCacheProperties properties, MeterRegistry metrics) {
        this.cache = cache; this.catalogue = catalogue; this.properties = properties; this.metrics = metrics;
        Gauge.builder("verse.query.cache.fence.pending", this, recovery -> recovery.pendingCount()).register(metrics);
    }

    /** 业务事务已结束，Redis 失败不能回写其结果；保留栅栏并显式排队重试。 */
    public void completed(String table, String token) {
        Completion completion = new Completion(table, token);
        if (finish(completion)) return;
        synchronized (pending) {
            if (pending.contains(completion)) return;
            if (pending.size() < properties.getFencePendingLimit()) pending.add(completion);
            else {
                count(table, "overflow");
                log.error("[query-cache] 结束记录重试队列已满，需停写核验恢复: table={}, token={}", table, token);
            }
        }
    }

    public void unknown(String table, String token) {
        count(table, "unknown");
        log.error("[query-cache] 无法确认写事务已结束，保留栅栏: table={}, token={}", table, token);
    }

    private boolean finish(Completion completion) {
        try {
            boolean done = cache.afterWrite(completion.table(), completion.token());
            if (done) count(completion.table(), "completed");
            return done;
        } catch (RuntimeException error) {
            count(completion.table(), "failure");
            log.warn("[query-cache] 已结束写入清理失败，保留栅栏并重试: table={}, token={}", completion.table(), completion.token(), error);
            return false;
        }
    }

    @Scheduled(fixedDelayString = "${verse.query-cache.fence-retry-delay-millis:5000}")
    public synchronized void recover() {
        List<Completion> local;
        synchronized (pending) { local = pending.stream().limit(properties.getFenceRetryBatch()).toList(); }
        for (Completion completion : local) {
            boolean done = finish(completion);
            synchronized (pending) {
                pending.remove(completion);
                if (!done) pending.add(completion);
            }
        }
        for (String table : catalogue.dependencies()) {
            Sample sample = samples.computeIfAbsent(table, this::sample);
            try {
                for (String token : cache.completedWriters(table, properties.getFenceRetryBatch())) {
                    if (cache.cleanupCompleted(table, token)) count(table, "recovered");
                }
                sample.values = cache.fenceStats(table);
            } catch (RuntimeException error) {
                sample.values = null;
                count(table, "scan_failure");
            }
        }
    }

    private Sample sample(String table) {
        Sample sample = new Sample();
        String[] names = {"writers", "oldest.seconds", "completed", "untracked"};
        for (int i = 0; i < names.length; i++) {
            int index = i;
            Gauge.builder("verse.query.cache.fence." + names[i], sample,
                    value -> value.value(index))
                    .tag("table", table).register(metrics);
        }
        return sample;
    }

    private int pendingCount() { synchronized (pending) { return pending.size(); } }
    private void count(String table, String outcome) {
        metrics.counter("verse.query.cache.fence.cleanup", "table", table, "outcome", outcome).increment();
    }
    private record Completion(String table, String token) { }
    private static class Sample {
        private volatile long[] values;
        private double value(int index) {
            long[] snapshot = values;
            return snapshot == null ? Double.NaN : snapshot[index] / (index == 1 ? 1000.0 : 1);
        }
    }
}
