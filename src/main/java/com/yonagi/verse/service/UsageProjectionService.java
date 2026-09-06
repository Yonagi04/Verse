package com.yonagi.verse.service;

import java.time.LocalDateTime;

/** 用量小时投影服务。 */
public interface UsageProjectionService {
    /** 重建半开时间窗内的小时投影。 */
    void rebuild(LocalDateTime from, LocalDateTime to);
}
