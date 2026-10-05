package com.yonagi.verse.async.outbox;

import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.service.impl.UserAccountCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 按有界批次补齐升级前已注销账号的清理任务；已完成或已暂存的不重复补发。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UserClosedCleanupJob {
    private final UserMapper users;
    private final UserAccountCleanupService cleanup;

    @Scheduled(fixedDelayString = "${verse.async.user-cleanup.backfill-delay-ms:60000}")
    public void backfill() {
        for (Long userId : users.selectUnscheduledClosedUsers(100)) {
            try {
                cleanup.enqueueHistorical(userId);
            } catch (RuntimeException error) {
                log.error("[user-cleanup] 历史账号任务暂存失败: userId={}", userId, error);
            }
        }
    }
}
