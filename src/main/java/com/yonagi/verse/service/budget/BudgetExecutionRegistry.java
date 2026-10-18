package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.service.forward.UpstreamAttemptControl;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.time.Instant;

/** 每次检查采用新 nonce 确认执行，心跳仅供运维，不作为放行证据。 */
@Component
@RequiredArgsConstructor
public class BudgetExecutionRegistry {
    private final RedissonClient redis;
    private final String owner = UUID.randomUUID().toString();
    private final String generation = UUID.randomUUID().toString();
    private final Map<String, LocalInvocation> executions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> replies = new ConcurrentHashMap<>();
    private int requestListener;
    private int replyListener;
    private static final String PREFIX = "verse:cost-budget:owner:";

    @PostConstruct public void listen() {
        requestListener = redis.getTopic(PREFIX + owner).addListener(String.class, (channel, message) -> {
            String[] parts = message.split("\\|", -1);
            if (parts.length != 4) return;
            boolean active = generation.equals(parts[2]) && active(parts[3]);
            redis.getTopic(PREFIX + parts[0] + ":reply").publishAsync(parts[1] + "|" + active);
        });
        replyListener = redis.getTopic(PREFIX + owner + ":reply").addListener(String.class, (channel, message) -> {
            String[] parts = message.split("\\|", -1);
            CompletableFuture<Boolean> reply = parts.length == 2 ? replies.get(parts[0]) : null;
            if (reply != null) reply.complete(Boolean.parseBoolean(parts[1]));
        });
    }
    @PreDestroy public void close() {
        redis.getTopic(PREFIX + owner).removeListener(requestListener);
        redis.getTopic(PREFIX + owner + ":reply").removeListener(replyListener);
    }
    public String owner() { return owner; }
    public String generation() { return generation; }
    public void register(String requestId) { register(requestId, null, null, null, BudgetInvocationMode.SYNC); }
    public void register(String requestId, Long tenantId, Long keyId, String eventId, BudgetInvocationMode mode) {
        if (executions.putIfAbsent(requestId, new LocalInvocation(requestId, tenantId, keyId, eventId,
                new InvocationExecutionState(mode))) != null) throw new CostBudgetUnavailableException();
    }
    public UpstreamAttemptControl attempt(String requestId) { return require(requestId).state().attempt(); }
    public void prepared(String requestId, Instant now) { require(requestId).state().prepared(now); }
    public void subscribe(String requestId) { require(requestId).state().subscribe(); }
    public boolean expireWaiting(String requestId, Instant now) {
        var execution = executions.get(requestId);
        return execution != null && execution.state().expireWaiting(now);
    }
    public void sent(String requestId) {
        attempt(requestId).startSending();
    }
    public boolean executed(String requestId) {
        var execution = executions.get(requestId);
        if (execution == null) return false;
        return execution.state().sent();
    }
    /** 和确认共享同步边界；收到终态立即停止 ACTIVE，先于任何数据库写入。 */
    public void finalizing(String requestId) {
        var execution = executions.get(requestId);
        if (execution == null) return;
        execution.state().finalizing();
    }
    public void remove(String requestId) {
        var execution = executions.remove(requestId);
        if (execution != null) execution.state().close();
    }
    public boolean noUpstream(String requestId) {
        var execution = executions.get(requestId);
        return execution != null && execution.state().noUpstream();
    }
    private boolean active(String requestId) {
        var execution = executions.get(requestId);
        return execution != null && execution.state().active();
    }
    private LocalInvocation require(String requestId) {
        var execution = executions.get(requestId);
        if (execution == null) throw new CostBudgetUnavailableException();
        return execution;
    }
    public LocalInvocation local(String requestId) { return executions.get(requestId); }
    public List<LocalInvocation> pendingCleanup(Instant now) {
        return executions.values().stream().filter(row -> row.tenantId() != null && row.state().cleanupPending(now))
                .sorted(Comparator.comparing(row -> row.state().cleanupDue())).limit(100).toList();
    }
    public void fail(String requestId, String stage, String code) {
        var execution = executions.get(requestId);
        if (execution != null) execution.state().fail(stage, code);
    }
    public InvocationExecutionState.Failure failure(String requestId) {
        var execution = executions.get(requestId);
        return execution == null ? null : execution.state().failure();
    }
    public record LocalInvocation(String requestId, Long tenantId, Long keyId, String eventId, InvocationExecutionState state) {}
    public boolean confirm(CostBudgetInvocationDO invocation) {
        return confirmed(List.of(invocation)).contains(invocation.getRequestId());
    }
    /** 一批远端证明共用 500ms 等待预算，避免持有数据库锁逐项等待。 */
    public Set<String> confirmed(Collection<? extends CostBudgetInvocationDO> invocations) {
        Set<String> confirmed = new HashSet<>();
        Map<String, CompletableFuture<Boolean>> pending = new HashMap<>();
        Map<String, String> nonces = new HashMap<>();
        try {
            for (var invocation : invocations) {
                String id = invocation.getRequestId();
                if (owner.equals(invocation.getOwnerId())) {
                    if (generation.equals(invocation.getOwnerGeneration()) && active(id)) confirmed.add(id);
                    continue;
                }
                if (pending.containsKey(id)) continue;
                String nonce = UUID.randomUUID().toString();
                var reply = new CompletableFuture<Boolean>();
                pending.put(id, reply); nonces.put(id, nonce); replies.put(nonce, reply);
                try {
                    redis.getTopic(PREFIX + invocation.getOwnerId()).publishAsync(owner + "|" + nonce + "|"
                            + invocation.getOwnerGeneration() + "|" + id)
                            .whenComplete((count, error) -> { if (error != null) reply.complete(false); });
                } catch (RuntimeException failure) { reply.complete(false); }
            }
            CompletableFuture.allOf(pending.values().toArray(CompletableFuture[]::new)).get(500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally { nonces.values().forEach(replies::remove); }
        pending.forEach((id, reply) -> { if (Boolean.TRUE.equals(reply.getNow(false))) confirmed.add(id); });
        return Set.copyOf(confirmed);
    }
}
