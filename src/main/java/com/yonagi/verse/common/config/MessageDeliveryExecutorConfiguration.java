package com.yonagi.verse.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/** 独立通道隔离，不允许饱和时回退到 HTTP/调度线程执行。 */
@Configuration(proxyBeanMethods = false)
public class MessageDeliveryExecutorConfiguration {
    @Bean("smsDeliveryExecutor")
    public ThreadPoolTaskExecutor smsDeliveryExecutor(MessagingProperties properties) { return pool(properties, "verse-sms-send-"); }

    @Bean("emailDeliveryExecutor")
    public ThreadPoolTaskExecutor emailDeliveryExecutor(MessagingProperties properties) { return pool(properties, "verse-email-send-"); }

    private ThreadPoolTaskExecutor pool(MessagingProperties properties, String prefix) {
        properties.validateCommon();
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getMaxConcurrency());
        executor.setMaxPoolSize(properties.getMaxConcurrency());
        executor.setQueueCapacity(properties.getAsync().getQueueCapacity());
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(70);
        return executor;
    }
}
