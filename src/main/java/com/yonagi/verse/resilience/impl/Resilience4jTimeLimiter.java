package com.yonagi.verse.resilience.impl;

import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.common.convention.exception.AbstractException;
import com.yonagi.verse.service.forward.UpstreamAttemptControl;
import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import com.yonagi.verse.service.forward.UpstreamFailureException;
import com.yonagi.verse.service.forward.UpstreamErrors;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 同步上游调用的等待时限与本机容量；取消后未退出的工作线程仍占用容量。 */
@Slf4j
@Component
public class Resilience4jTimeLimiter {
    private final TimeLimiter timeLimiter;
    private final ExecutorService workerPool;
    private final long shutdownGraceMs;

    public Resilience4jTimeLimiter(long timeLimitMs) { this(timeLimitMs, 32, 5000); }

    @Autowired
    public Resilience4jTimeLimiter(
            @Value("${verse.llm.upstream.time-limit-ms:120000}") long timeLimitMs,
            @Value("${verse.llm.upstream.max-concurrency:32}") int maxConcurrency,
            @Value("${verse.llm.upstream.shutdown-grace-ms:5000}") long shutdownGraceMs) {
        if (timeLimitMs <= 0 || maxConcurrency <= 0 || shutdownGraceMs < 0) {
            throw new IllegalArgumentException("上游超时与执行容量必须为正数，关闭等待时间不能为负数");
        }
        this.shutdownGraceMs = shutdownGraceMs;
        timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(timeLimitMs)).cancelRunningFuture(false).build());
        AtomicInteger threadIdx = new AtomicInteger();
        workerPool = new ThreadPoolExecutor(0, maxConcurrency, 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> {
                    Thread thread = new Thread(task, "llm-upstream-" + threadIdx.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public <T> T execute(UpstreamAttemptControl control, Callable<T> callable) {
        java.util.Objects.requireNonNull(control);
        if (Thread.currentThread().isInterrupted()) {
            throw UpstreamErrors.cancelled(control.cancel());
        }
        Future<T> future;
        try { future = workerPool.submit(() -> {
            control.checkActive();
            try { return callable.call(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw UpstreamErrors.cancelled(control.cancel());
            }
        }); }
        catch (RejectedExecutionException rejected) {
            throw failure(LlmForwardErrorCodeEnum.UPSTREAM_CAPACITY_EXCEEDED, UpstreamExecutionOutcome.NOT_SENT);
        }
        try {
            return timeLimiter.executeFutureSupplier(() -> future);
        } catch (TimeoutException timeout) {
            UpstreamExecutionOutcome outcome = control.cancel();
            future.cancel(true);
            throw failure(LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT, outcome);
        } catch (InterruptedException interrupted) {
            UpstreamExecutionOutcome outcome = control.cancel();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw UpstreamErrors.cancelled(outcome);
        } catch (AbstractException original) {
            // Resilience4j 已展开 ExecutionException；保持业务错误和失败证据。
            throw original;
        } catch (RuntimeException unexpected) {
            throw failure(LlmForwardErrorCodeEnum.FORWARD_FAILED, control.cancel());
        } catch (Exception checked) {
            throw failure(LlmForwardErrorCodeEnum.FORWARD_FAILED, control.cancel());
        }
    }

    private UpstreamFailureException failure(LlmForwardErrorCodeEnum code, UpstreamExecutionOutcome outcome) {
        return new UpstreamFailureException(code.message(), code, false, outcome);
    }

    @PreDestroy
    public void close() {
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(shutdownGraceMs, TimeUnit.MILLISECONDS)) {
                workerPool.shutdownNow();
                log.warn("[llm-forward] 上游执行池关闭等待结束，已请求中断；远端执行状态仍需核验");
            }
        } catch (InterruptedException interrupted) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
