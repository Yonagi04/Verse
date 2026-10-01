package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.dao.entity.PlaygroundAttemptDO;
import com.yonagi.verse.dao.mapper.PlaygroundAttemptMapper;
import com.yonagi.verse.dao.mapper.PlaygroundWorkspaceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 先锁住工作区，再收敛尝试与释放锁，避免多栏终态并发漏释放。 */
@Service
@RequiredArgsConstructor
public class PlaygroundAttemptFinalizer {
    private final PlaygroundWorkspaceMapper workspaceMapper;
    private final PlaygroundAttemptMapper attemptMapper;

    @Transactional(rollbackFor = Exception.class)
    public boolean stopPending(PlaygroundAttemptDO attempt) {
        workspaceMapper.lock(attempt.getTenantId(), attempt.getOwnerUserId(), attempt.getWorkspaceId());
        if (attemptMapper.stopPending(attempt.getTenantId(), attempt.getOwnerUserId(), attempt.getAttemptId()) != 1) return false;
        workspaceMapper.release(attempt.getWorkspaceId());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public void finish(PlaygroundAttemptDO attempt, String reply, String status, Long firstContentMs,
                       Long durationMs, String errorJson) {
        workspaceMapper.lock(attempt.getTenantId(), attempt.getOwnerUserId(), attempt.getWorkspaceId());
        PlaygroundAttemptDO current = attemptMapper.selectOne(Wrappers.lambdaQuery(PlaygroundAttemptDO.class)
                .eq(PlaygroundAttemptDO::getAttemptId, attempt.getAttemptId())
                .eq(PlaygroundAttemptDO::getTenantId, attempt.getTenantId())
                .eq(PlaygroundAttemptDO::getOwnerUserId, attempt.getOwnerUserId()));
        if (current != null && "STOPPING".equals(current.getStatus()) && "COMPLETED".equals(status)) status = "STOPPED";
        attemptMapper.update(Wrappers.lambdaUpdate(PlaygroundAttemptDO.class)
                .eq(PlaygroundAttemptDO::getAttemptId, attempt.getAttemptId())
                .eq(PlaygroundAttemptDO::getTenantId, attempt.getTenantId())
                .eq(PlaygroundAttemptDO::getOwnerUserId, attempt.getOwnerUserId())
                .in(PlaygroundAttemptDO::getStatus, "PENDING", "STREAMING", "STOPPING")
                .set(PlaygroundAttemptDO::getReply, reply).set(PlaygroundAttemptDO::getStatus, status)
                .set(PlaygroundAttemptDO::getFirstContentMs, firstContentMs)
                .set(PlaygroundAttemptDO::getDurationMs, durationMs)
                .set(PlaygroundAttemptDO::getErrorJson, errorJson)
                .set(PlaygroundAttemptDO::getFinishedAt, LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
                .set(PlaygroundAttemptDO::getUpdateTime, LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
        workspaceMapper.release(attempt.getWorkspaceId());
    }
}
