package com.yonagi.verse.service.impl;

import com.yonagi.verse.dao.mapper.PlaygroundSessionMapper;
import com.yonagi.verse.dao.mapper.PlaygroundTurnMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 轮次终态与会话生成锁在同一数据库事务中提交。 */
@Service
@RequiredArgsConstructor
public class PlaygroundTurnFinalizer {
    private final PlaygroundTurnMapper turnMapper;
    private final PlaygroundSessionMapper sessionMapper;

    @Transactional(rollbackFor = Exception.class)
    public boolean finish(Long tenantId, Long ownerId, Long sessionId, Long turnId,
                          String reply, String status) {
        if (turnMapper.finish(tenantId, ownerId, sessionId, turnId, reply, status) != 1) return false;
        if (sessionMapper.release(tenantId, ownerId, sessionId) != 1) {
            throw new IllegalStateException("PlayGround 会话生成锁释放失败");
        }
        return true;
    }
}
