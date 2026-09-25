package com.yonagi.verse.service.impl;

import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Date;

/** 鉴权成功后异步记录 API Key 最近使用时间，不阻塞模型请求。 */
@Slf4j
@Component
public class ApiKeyUsageRecorder {

    private final ApiKeyMapper apiKeyMapper;
    private final TaskExecutor executor;

    public ApiKeyUsageRecorder(ApiKeyMapper apiKeyMapper,
                               @Qualifier("apiKeyUsageExecutor") TaskExecutor executor) {
        this.apiKeyMapper = apiKeyMapper;
        this.executor = executor;
    }

    public void record(Long apiKeyId, Date usedAt) {
        try {
            executor.execute(() -> {
                try {
                    // 使用鉴权通过时的时间，条件更新保证并发回写不会覆盖更新的记录。
                    apiKeyMapper.updateLastUsedAtIfLater(apiKeyId, usedAt);
                } catch (Exception e) {
                    log.warn("[api-key] 最近使用时间回写失败: apiKeyId={}", apiKeyId, e);
                }
            });
        } catch (RuntimeException e) {
            log.warn("[api-key] 最近使用时间异步任务提交失败: apiKeyId={}", apiKeyId, e);
        }
    }
}
