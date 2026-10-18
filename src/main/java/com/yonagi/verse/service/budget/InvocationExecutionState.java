package com.yonagi.verse.service.budget;

import com.yonagi.verse.service.forward.UpstreamAttemptControl;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import java.time.Instant;
import java.util.Objects;

/** 一个逻辑调用的执行规则。线程开始、数据库准入均不能作为已发送证据。 */
public final class InvocationExecutionState {
    private enum Phase { PREPARING, WAITING_SUBSCRIPTION, ACTIVE, CLEANUP_PENDING, FINALIZING, CLOSED }
    private Phase phase;
    private Instant waitingSince;
    private Instant cleanupDue = Instant.MIN;
    private boolean sent;
    private Attempt current;
    private Failure failure;
    private UpstreamExecutionOutcome lastOutcome = UpstreamExecutionOutcome.NOT_SENT;

    public InvocationExecutionState(BudgetInvocationMode mode) {
        phase = mode == BudgetInvocationMode.STREAM ? Phase.PREPARING : Phase.ACTIVE;
    }

    public synchronized UpstreamAttemptControl attempt() {
        if (phase != Phase.ACTIVE || current != null && !current.safeToRetry()) throw unavailable();
        current = new Attempt();
        return current;
    }

    public synchronized void prepared(Instant now) {
        if (phase != Phase.PREPARING) throw unavailable();
        waitingSince = Objects.requireNonNull(now);
        phase = Phase.WAITING_SUBSCRIPTION;
    }

    public synchronized void subscribe() {
        if (phase != Phase.WAITING_SUBSCRIPTION) throw unavailable();
        phase = Phase.ACTIVE;
    }

    public synchronized boolean expireWaiting(Instant now) {
        return phase == Phase.WAITING_SUBSCRIPTION && !waitingSince.plusSeconds(30).isAfter(now) && noUpstream();
    }

    public synchronized boolean active() {
        return phase == Phase.ACTIVE || phase == Phase.PREPARING || phase == Phase.WAITING_SUBSCRIPTION;
    }
    public synchronized boolean sent() { return sent; }
    public synchronized UpstreamExecutionOutcome outcome() {
        return current != null && current.sending && current.outcome == null ? UpstreamExecutionOutcome.UNKNOWN : lastOutcome;
    }

    public synchronized void finalizing() {
        if (phase != Phase.CLOSED && phase != Phase.CLEANUP_PENDING) phase = Phase.FINALIZING;
    }

    public synchronized boolean noUpstream() {
        if (sent || phase == Phase.CLOSED || phase == Phase.FINALIZING) return false;
        phase = Phase.CLEANUP_PENDING;
        if (current != null) current.cancelled = true;
        return true;
    }

    public synchronized boolean cleanupPending(Instant now) {
        return phase == Phase.CLEANUP_PENDING && !cleanupDue.isAfter(now);
    }
    public synchronized void deferCleanup(Instant now) { cleanupDue = now.plusSeconds(10); }
    public synchronized Instant cleanupDue() { return cleanupDue; }
    public synchronized void close() { phase = Phase.CLOSED; }

    /** 只在逻辑终态确定时记录；安全重试的中间错误不占据首因。 */
    public synchronized void fail(String stage, String code) {
        if (failure == null && code != null) failure = new Failure(stage, code);
    }
    public synchronized Failure failure() { return failure; }
    public record Failure(String stage, String code) {}

    private CostBudgetUnavailableException unavailable() { return new CostBudgetUnavailableException(); }

    private final class Attempt implements UpstreamAttemptControl {
        private boolean sending;
        private boolean cancelled;
        private UpstreamExecutionOutcome outcome;

        @Override public void checkActive() {
            synchronized (InvocationExecutionState.this) {
                if (current != this || cancelled || phase != Phase.ACTIVE) throw unavailable();
            }
        }
        @Override public void startSending() {
            synchronized (InvocationExecutionState.this) {
                checkActive();
                if (sending || outcome != null) throw unavailable();
                sending = true;
                sent = true;
            }
        }
        @Override public UpstreamExecutionOutcome cancel() {
            synchronized (InvocationExecutionState.this) {
                cancelled = true;
                // 迟到的旧尝试不能取消新尝试，也不能借用新尝试的发送证据。
                UpstreamExecutionOutcome result = outcome != null ? outcome
                        : sending ? UpstreamExecutionOutcome.UNKNOWN : UpstreamExecutionOutcome.NOT_SENT;
                if (current == this && phase != Phase.CLOSED) {
                    if (!sent) phase = Phase.CLEANUP_PENDING;
                    else phase = Phase.FINALIZING;
                }
                return result;
            }
        }
        @Override public void complete(UpstreamExecutionOutcome result) {
            synchronized (InvocationExecutionState.this) {
                if (current == this && !cancelled && outcome == null) {
                    outcome = Objects.requireNonNull(result);
                    lastOutcome = result;
                }
            }
        }
        private boolean safeToRetry() {
            return !cancelled && (outcome == UpstreamExecutionOutcome.NOT_SENT || outcome == UpstreamExecutionOutcome.REJECTED);
        }
    }
}
