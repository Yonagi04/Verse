package com.yonagi.verse.resilience.impl;

import com.yonagi.verse.resilience.api.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 熔断实现 — 包装 Resilience4j {@link CircuitBreakerRegistry}，按 serviceId 取/建模型级熔断器，
 * 熔断参数从 {@code verse.llm.circuit-breaker} 全局默认读取，同名实例共享同一配置。
 *
 * @author Yonagi
 */
@Slf4j
@Component
public class Resilience4jCircuitBreaker implements CircuitBreaker {

    private final CircuitBreakerRegistry registry;
    private final ConcurrentMap<String, io.github.resilience4j.circuitbreaker.CircuitBreaker> breakers =
            new ConcurrentHashMap<>();

    public Resilience4jCircuitBreaker(
            @Value("${verse.llm.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${verse.llm.circuit-breaker.sliding-window-size:20}") int slidingWindowSize,
            @Value("${verse.llm.circuit-breaker.minimum-number-of-calls:10}") int minimumNumberOfCalls,
            @Value("${verse.llm.circuit-breaker.wait-duration-open-ms:30000}") long waitDurationOpenMs) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofMillis(waitDurationOpenMs))
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
    }

    @Override
    public boolean isOpen(String serviceId) {
        return breaker(serviceId).getState()
                == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN;
    }

    @Override
    public void recordSuccess(String serviceId) {
        breaker(serviceId).onSuccess(0, TimeUnit.NANOSECONDS);
    }

    @Override
    public void recordFailure(String serviceId) {
        breaker(serviceId).onError(0, TimeUnit.NANOSECONDS, new RuntimeException("upstream failure"));
    }

    private io.github.resilience4j.circuitbreaker.CircuitBreaker breaker(String serviceId) {
        return breakers.computeIfAbsent(serviceId, registry::circuitBreaker);
    }
}
