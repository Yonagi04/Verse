package com.yonagi.verse.common.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
    private volatile double activeScopes;
    private volatile double[] scopeScan = {0, 0, 0, 0};

    public QueryFenceRecovery(QueryCache cache, QueryCatalogue catalogue,
                              QueryCacheProperties properties, MeterRegistry metrics) {
        this.cache = cache; this.catalogue = catalogue; this.properties = properties; this.metrics = metrics;
        Gauge.builder("verse.query.cache.fence.pending", this, recovery -> recovery.pendingCount()).register(metrics);
        Gauge.builder("verse.query.cache.scope.active", this, recovery -> recovery.activeScopes).register(metrics);
        String[] scopeNames = {"writers", "oldest.seconds", "completed", "untracked"};
        for (int i = 0; i < scopeNames.length; i++) {
            int index = i;
            Gauge.builder("verse.query.cache.scope.scan." + scopeNames[i], this,
                    recovery -> recovery.scopeScan[index]).register(metrics);
        }
    }

    /** 业务事务已结束，Redis 失败不能回写其结果；保留栅栏并显式排队重试。 */
    public void completed(String table, String token) {
        completed(QueryDependency.table(table), token);
    }

    /** 事务结束证明按完整依赖保存，不能用另一范围的完成状态替代。 */
    public void completed(QueryDependency dependency, String token) {
        Completion completion = new Completion(dependency, token);
        if (finish(completion)) {
            return;
        }
        synchronized (pending) {
            if (pending.contains(completion)) {
                return;
            }
            if (pending.size() < properties.getFencePendingLimit()) {
                pending.add(completion);
            } else {
                count(dependency, "overflow");
                log.error("[query-cache] 结束记录重试队列已满，需停写核验恢复: resource={}, token={}",
                        dependency.identity(), token);
            }
        }
    }

    public void unknown(String table, String token) {
        count(table, "unknown");
        log.error("[query-cache] 无法确认写事务已结束，保留栅栏: table={}, token={}", table, token);
    }

    /** 未知写结果仅记录诊断，不能按年龄或节点存活状态释放。 */
    public void unknown(QueryDependency dependency, String token) {
        count(dependency, "unknown");
        log.error("[query-cache] 无法确认写事务已结束，保留栅栏: resource={}, token={}",
                dependency.identity(), token);
    }

    private boolean finish(Completion completion) {
        try {
            QueryDependency dependency = completion.dependency();
            boolean done = dependency.isTable() ? cache.afterWrite(dependency.table(), completion.token())
                    : cache.afterWrite(dependency, completion.token());
            if (done) {
                count(dependency, "completed");
            }
            return done;
        } catch (RuntimeException error) {
            count(completion.dependency(), "failure");
            log.warn("[query-cache] 已结束写入清理失败，保留栅栏并重试: resource={}, token={}",
                    completion.dependency().identity(), completion.token(), error);
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
                if (!done) {
                    pending.add(completion);
                }
            }
        }
        for (String table : catalogue.dependencies()) {
            Sample sample = samples.computeIfAbsent(table, this::sample);
            try {
                for (String token : cache.completedWriters(table, properties.getFenceRetryBatch())) {
                    if (cache.cleanupCompleted(table, token)) {
                        count(table, "recovered");
                    }
                }
                sample.values = cache.fenceStats(table);
            } catch (RuntimeException error) {
                sample.values = null;
                count(table, "scan_failure");
            }
        }
        recoverScopes();
    }

    private void recoverScopes() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.getScopeRecoveryBudgetMillis());
        double[] values = new double[4];
        boolean failed = false;
        int visited = 0;
        try {
            activeScopes = cache.activeDependencyCount();
            for (String identity : cache.activeDependencies(properties.getScopeRecoveryBatch())) {
                if (visited > 0 && System.nanoTime() >= deadline) {
                    break;
                }
                visited++;
                try {
                    QueryDependency dependency = QueryDependency.fromIdentity(identity);
                    if (dependency.isTable()) {
                        throw new IllegalArgumentException("动态恢复只接受 v2 资源");
                    }
                    for (String token : cache.completedWriters(dependency, properties.getFenceRetryBatch())) {
                        if (cache.cleanupCompleted(dependency, token)) {
                            count(dependency, "recovered");
                        }
                    }
                    cache.removeEmptyRegistration(dependency);
                    long[] stats = cache.fenceStats(dependency);
                    values[0] += stats[0];
                    values[1] = Math.max(values[1], stats[1] / 1000.0);
                    values[2] += stats[2];
                    values[3] += stats[3];
                } catch (RuntimeException error) {
                    if (!failed) {
                        log.warn("[query-cache] 作用域恢复失败，保留记录并轮转: resource={}",
                                identity.substring(0, Math.min(identity.length(), 192)), error);
                    }
                    failed = true;
                    metrics.counter("verse.query.cache.scope.scan.failure").increment();
                } finally {
                    // 即使资源损坏也轮转；XX 不会重新插入已被 FINISH 注销的资源。
                    try {
                        cache.rotateDependency(identity);
                    } catch (RuntimeException error) {
                        if (!failed) {
                            log.warn("[query-cache] 作用域恢复轮转失败，下一轮重试", error);
                        }
                        failed = true;
                        metrics.counter("verse.query.cache.scope.rotate.failure").increment();
                    }
                }
            }
        } catch (RuntimeException error) {
            if (!failed) {
                log.warn("[query-cache] 作用域恢复扫描失败，保留全部记录", error);
            }
            failed = true;
            activeScopes = Double.NaN;
            metrics.counter("verse.query.cache.scope.scan.failure").increment();
        }
        if (failed) {
            Arrays.fill(values, Double.NaN);
        }
        scopeScan = values;
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

    private void count(QueryDependency dependency, String outcome) {
        if (dependency.isTable()) {
            count(dependency.table(), outcome);
            return;
        }
        String table = catalogue.dependsOn(dependency.table()) ? dependency.table() : "unknown";
        metrics.counter("verse.query.cache.scope.cleanup", "table", table,
                "scopeKind", dependency.scope().kind().name(), "outcome", outcome).increment();
    }

    private record Completion(QueryDependency dependency, String token) { }
    private static class Sample {
        private volatile long[] values;
        private double value(int index) {
            long[] snapshot = values;
            return snapshot == null ? Double.NaN : snapshot[index] / (index == 1 ? 1000.0 : 1);
        }
    }
}
