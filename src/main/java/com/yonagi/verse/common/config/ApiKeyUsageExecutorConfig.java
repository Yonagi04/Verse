package com.yonagi.verse.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** API Key 最近使用时间的独立异步写入线程池。 */
@Configuration
public class ApiKeyUsageExecutorConfig {

    @Bean("apiKeyUsageExecutor")
    public ThreadPoolTaskExecutor apiKeyUsageExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(1024);
        executor.setThreadNamePrefix("api-key-usage-");
        executor.initialize();
        return executor;
    }
}
