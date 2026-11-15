package com.yonagi.verse.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/** 调度、发现和发件隔离，默认调度器也不借用技能续租线程。 */
@Configuration
public class CostReconciliationExecutorConfiguration {
    @Bean
    public ThreadPoolTaskExecutor costReconciliationExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("verse-reconciliation-discovery-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    public ThreadPoolTaskScheduler costReconciliationTaskScheduler() {
        return scheduler(1, "verse-reconciliation-trigger-");
    }

    @Bean
    public ThreadPoolTaskScheduler costReconciliationRelayScheduler() {
        return scheduler(1, "verse-reconciliation-relay-");
    }

    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        return scheduler(2, "verse-task-");
    }

    private ThreadPoolTaskScheduler scheduler(int threads, String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(threads);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
