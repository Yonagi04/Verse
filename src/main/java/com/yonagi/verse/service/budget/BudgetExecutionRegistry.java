package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/** 每次检查采用新 nonce 确认执行，心跳仅供运维，不作为放行证据。 */
@Component
@RequiredArgsConstructor
public class BudgetExecutionRegistry {
    private final RedissonClient redis;
    private final String owner = UUID.randomUUID().toString();
    private final String generation = UUID.randomUUID().toString();
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();
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
    public void register(String requestId) { executions.putIfAbsent(requestId, new Execution()); }
    public void sent(String requestId) {
        Execution execution = executions.get(requestId);
        if (execution == null) throw new IllegalStateException("invocation permit expired");
        synchronized (execution) {
            if (!execution.running) throw new IllegalStateException("invocation permit no longer active");
            execution.sent = true;
        }
    }
    public boolean executed(String requestId) {
        Execution execution = executions.get(requestId);
        if (execution == null) return false;
        synchronized (execution) { return execution.sent; }
    }
    /** 和确认共享同步边界；收到终态立即停止 ACTIVE，先于任何数据库写入。 */
    public void finalizing(String requestId) {
        Execution execution = executions.get(requestId);
        if (execution == null) return;
        synchronized (execution) { execution.running = false; }
    }
    public void remove(String requestId) { executions.remove(requestId); }
    public boolean noUpstream(String requestId) {
        Execution execution = executions.get(requestId);
        if (execution == null) return false;
        synchronized (execution) {
            if (execution.sent || !execution.running) return false;
            execution.running = false;
            return true;
        }
    }
    private boolean active(String requestId) {
        Execution execution = executions.get(requestId);
        if (execution == null) return false;
        synchronized (execution) { return execution.running; }
    }
    public boolean confirm(CostBudgetInvocationDO invocation) {
        if (owner.equals(invocation.getOwnerId())) {
            return generation.equals(invocation.getOwnerGeneration()) && active(invocation.getRequestId());
        }
        String nonce = UUID.randomUUID().toString();
        CompletableFuture<Boolean> reply = new CompletableFuture<>(); replies.put(nonce, reply);
        try {
            redis.getTopic(PREFIX + invocation.getOwnerId()).publishAsync(owner + "|" + nonce + "|"
                    + invocation.getOwnerGeneration() + "|" + invocation.getRequestId())
                    .whenComplete((count, error) -> { if (error != null) reply.complete(false); });
            return reply.get(500, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        } finally { replies.remove(nonce); }
    }
    private static final class Execution {
        private boolean running = true;
        private boolean sent;
    }
}
