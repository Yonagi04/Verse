package com.yonagi.verse.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.concurrent.ThreadPoolExecutor;

/** 有界导入与校验资源预算，不按部署环境提供功能开关。 */
@Configuration
public class SkillExecutorConfig {
    /** @return ZIP接收结束后的有界存储工作，不在Servlet非阻塞读取回调内调用S3 */
    @Bean("skillUploadExecutor")
    public ThreadPoolTaskExecutor skillUploadExecutor() {
        return executor(2, 0, "skill-upload-");
    }

    /** @return 独立租约续期调度器，避免上传与包解析阻塞续租 */
    @Bean("skillLeaseScheduler")
    public ThreadPoolTaskScheduler skillLeaseScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("skill-lease-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(5);
        return scheduler;
    }

    /** @return 最多两个并行导入任务，无排队内存任务，DB保存可恢复工作 */
    @Bean("skillImportExecutor")
    public ThreadPoolTaskExecutor skillImportExecutor() {
        return executor(2, 0, "skill-import-");
    }

    private ThreadPoolTaskExecutor executor(int threads, int queue, String prefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
