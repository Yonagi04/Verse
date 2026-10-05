package com.yonagi.verse.service.impl;

import com.yonagi.verse.dao.mapper.TokenUsageHourlyAggMapper;
import com.yonagi.verse.service.UsageProjectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDateTime;

/** 用量小时投影实现，删除并重建绝对值以保证幂等。 */
@Service
@RequiredArgsConstructor
public class UsageProjectionServiceImpl implements UsageProjectionService {
    private final TokenUsageHourlyAggMapper mapper;

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void rebuild(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("用量投影时间范围不合法");
        }
        if (mapper.lockProjection() == null) throw new IllegalStateException("缺少统计投影锁初始化行");
        mapper.deleteRange(from, to);
        mapper.rebuildRange(from, to);
    }
}
