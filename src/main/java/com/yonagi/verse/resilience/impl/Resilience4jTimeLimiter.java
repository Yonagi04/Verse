package com.yonagi.verse.resilience.impl;

import com.yonagi.verse.common.enums.LlmForwardErrorCodeEnum;
import com.yonagi.verse.service.forward.UpstreamFailureException;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 上游调用超时封装 — 包装 Resilience4j {@link TimeLimiter}，为同步阻塞的上游 HTTP 调用
 * 提供硬性的「总耗时」上限，区别于 {@code SimpleClientHttpRequestFactory} read-timeout 的
 * 「两次数据到达间隔」语义（后者在慢速流式响应下可能永不触发）。
 *
 * <p>阻塞调用交由独立缓存线程池执行，超时由 TimeLimiter 内部调度器触发并取消运行中的 Future；
 * 线程池使用守护线程 + 空闲回收，避免随上游抖动无限膨胀。</p>
 *
 * @author Yonagi
 */
@Component
public class Resilience4jTimeLimiter {

    private final TimeLimiter timeLimiter;
    private final ExecutorService workerPool;

    public Resilience4jTimeLimiter(
            @Value("${verse.llm.upstream.time-limit-ms:120000}") long timeLimitMs) {
        this.timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(timeLimitMs))
                .cancelRunningFuture(true)
                .build());
        AtomicInteger threadIdx = new AtomicInteger();
        this.workerPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "llm-upstream-" + threadIdx.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 在硬性超时约束下执行一次上游调用。
     *
     * @param callable 阻塞的上游调用
     * @return 上游响应
     * @throws UpstreamFailureException 超时（可重试）或执行失败
     */
    public <T> T execute(Callable<T> callable) {
        try {
            return timeLimiter.executeFutureSupplier(() -> workerPool.submit(callable));
        } catch (TimeoutException e) {
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT.message(),
                    LlmForwardErrorCodeEnum.UPSTREAM_TIMEOUT, true);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UpstreamFailureException ufe) {
                throw ufe;
            }
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.FORWARD_FAILED.message(),
                    LlmForwardErrorCodeEnum.FORWARD_FAILED, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.FORWARD_FAILED.message(),
                    LlmForwardErrorCodeEnum.FORWARD_FAILED, true);
        } catch (Exception e) {
            throw new UpstreamFailureException(LlmForwardErrorCodeEnum.FORWARD_FAILED.message(),
                    LlmForwardErrorCodeEnum.FORWARD_FAILED, true);
        }
    }
}
