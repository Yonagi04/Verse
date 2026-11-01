package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/** 远程获取使用独立有界槽位，DB保存排队真相，拒绝执行不丢任务。 */
@Component
@Slf4j
public class SkillGithubImportWorker {
    private final SkillImportTaskMapper tasks;
    private final SkillGithubImportProcessingService processing;
    private final ThreadPoolTaskExecutor executor;

    public SkillGithubImportWorker(SkillImportTaskMapper tasks, SkillGithubImportProcessingService processing,
                                   @Qualifier("skillGithubExecutor") ThreadPoolTaskExecutor executor) {
        this.tasks = tasks;
        this.processing = processing;
        this.executor = executor;
    }

    @Scheduled(fixedDelay = 5000L)
    public void dispatch() {
        try {
            for (Long jobId : tasks.selectGithubEligibleIds(LocalDateTime.now(ZoneOffset.UTC))) {
                try {
                    executor.execute(() -> processing.process(jobId));
                } catch (RejectedExecutionException exception) {
                    return;
                }
            }
        } catch (RuntimeException exception) {
            log.warn("GitHub任务分派失败: type={}", exception.getClass().getSimpleName());
        }
    }
}
