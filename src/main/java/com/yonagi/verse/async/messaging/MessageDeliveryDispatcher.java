package com.yonagi.verse.async.messaging;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.enums.MessageChannel;
import com.yonagi.verse.service.messaging.SendRecordStore;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** 只投递记录 ID；内存队列拒绝时数据库任务仍为 QUEUED，下一轮可以恢复。 */
@Component
@Slf4j
public class MessageDeliveryDispatcher {
    private final SendRecordStore records;
    private final MessageDeliveryWorker worker;
    private final ThreadPoolTaskExecutor sms;
    private final ThreadPoolTaskExecutor email;
    private final MessagingProperties properties;
    private final MeterRegistry metrics;
    private final Set<String> dispatched = ConcurrentHashMap.newKeySet();
    private final Map<MessageChannel, AtomicLong> pending = new EnumMap<>(MessageChannel.class);

    public MessageDeliveryDispatcher(SendRecordStore records, MessageDeliveryWorker worker,
            @Qualifier("smsDeliveryExecutor") ThreadPoolTaskExecutor sms,
            @Qualifier("emailDeliveryExecutor") ThreadPoolTaskExecutor email, MessagingProperties properties, MeterRegistry metrics) {
        this.records = records; this.worker = worker; this.sms = sms; this.email = email; this.properties = properties; this.metrics = metrics;
        registerMetrics(MessageChannel.SMS, sms);
        registerMetrics(MessageChannel.EMAIL, email);
    }

    private void registerMetrics(MessageChannel channel, ThreadPoolTaskExecutor executor) {
        var backlog = new AtomicLong(); pending.put(channel, backlog);
        var tags = io.micrometer.core.instrument.Tags.of("channel", channel.name().toLowerCase(Locale.ROOT));
        metrics.gauge("verse.messaging.pending", tags, backlog);
        metrics.gauge("verse.messaging.executor.active", tags, executor, ThreadPoolTaskExecutor::getActiveCount);
        metrics.gauge("verse.messaging.executor.queued", tags, executor, ThreadPoolTaskExecutor::getQueueSize);
    }

    @Scheduled(fixedDelayString = "${verse.messaging.async.poll-interval-millis:500}")
    public void dispatch() {
        dispatch(MessageChannel.SMS, sms);
        dispatch(MessageChannel.EMAIL, email);
    }

    private void dispatch(MessageChannel channel, ThreadPoolTaskExecutor executor) {
        String label = channel.name().toLowerCase(Locale.ROOT);
        try {
            int recovered = records.recoverAbandoned(channel, new Date(System.currentTimeMillis() - properties.getAsync().getAbandonedAfter().toMillis()));
            if (recovered > 0) metrics.counter("verse.messaging.abandoned", "channel", label).increment(recovered);
            pending.get(channel).set(records.queuedCount(channel));
            var pool = executor.getThreadPoolExecutor();
            int slots = pool.getMaximumPoolSize() - pool.getActiveCount() + pool.getQueue().remainingCapacity();
            for (Long id : records.due(channel, slots)) {
                String key = channel.name() + ":" + id;
                if (!dispatched.add(key)) continue;
                try {
                    executor.execute(() -> {
                        try { worker.execute(channel, id); }
                        catch (RuntimeException failure) {
                            metrics.counter("verse.messaging.worker.failures", "channel", label).increment();
                            log.error("[messaging] 后台任务异常: channel={}, recordId={}", channel, id);
                        } finally { dispatched.remove(key); }
                    });
                } catch (RejectedExecutionException full) {
                    dispatched.remove(key);
                    metrics.counter("verse.messaging.dispatch.deferred", "channel", label).increment();
                    break;
                }
            }
        } catch (RuntimeException failure) {
            metrics.counter("verse.messaging.dispatch.failures", "channel", label).increment();
            log.error("[messaging] 扫描失败: channel={}, reason={}", channel, failure.getClass().getSimpleName());
        }
    }
}
