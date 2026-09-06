package com.yonagi.verse.service.impl;

import com.yonagi.verse.dao.mapper.TokenUsageHourlyAggMapper;
import com.yonagi.verse.service.UsageProjectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 用量小时投影实现，删除并重建绝对值以保证幂等。 */
@Service
@RequiredArgsConstructor
public class UsageProjectionServiceImpl implements UsageProjectionService {
    private final TokenUsageHourlyAggMapper mapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuild(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("用量投影时间范围不合法");
        }
        mapper.deleteRange(from, to);
        mapper.rebuildRange(from, to);
    }
}
