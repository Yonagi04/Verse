package com.yonagi.verse.service.skillhub;

import com.yonagi.verse.dao.mapper.skillhub.SkillImportTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.RejectedExecutionException;

/** DB保存任务真相；此协调器只做有界周期分派，单次导入业务归应用Service。 */
@Component
@Slf4j
public class SkillImportWorker {
    private static final long DISPATCH_INTERVAL_MILLIS = 5000L;
    private final SkillImportTaskMapper taskMapper;
    private final SkillImportProcessingService processingService;
    private final ThreadPoolTaskExecutor executor;
    private final SkillGithubImportProcessingService githubProcessing;

    /** 持久化候选不搬入无界内存队列，执行器关闭时停止分派。 */
    public SkillImportWorker(SkillImportTaskMapper taskMapper, SkillImportProcessingService processingService,
                              @Qualifier("skillImportExecutor") ThreadPoolTaskExecutor executor,
                              SkillGithubImportProcessingService githubProcessing) {
        this.taskMapper = taskMapper;
        this.processingService = processingService;
        this.executor = executor;
        this.githubProcessing = githubProcessing;
    }

    /** 最多查询两个候选，拒绝执行时保留DB任务等待下次调度。 */
    @Scheduled(fixedDelay = DISPATCH_INTERVAL_MILLIS)
    public void dispatch() {
        try {
            for (Long jobId : taskMapper.selectEligibleIds(LocalDateTime.now(ZoneOffset.UTC))) {
                try {
                    executor.execute(() -> {
                        com.yonagi.verse.dao.entity.skillhub.SkillImportTaskDO reference = taskMapper.selectById(jobId);
                        if (reference != null && "GITHUB".equals(reference.getSourceType())) {
                            githubProcessing.process(jobId);
                        } else {
                            processingService.process(jobId);
                        }
                    });
                } catch (RejectedExecutionException exception) {
                    return;
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Skill任务调度失败: type={}", exception.getClass().getSimpleName());
        }
    }
}
