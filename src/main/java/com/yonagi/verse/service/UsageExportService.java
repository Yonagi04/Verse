package com.yonagi.verse.service;

import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.common.enums.UsageExportType;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.service.reporting.UsageExportTarget;

import java.time.LocalDateTime;

/** 用量 Excel 报表导出服务。 */
public interface UsageExportService {
    /**
     * 将指定报表同步写入目标。流的生命周期由目标提供方管理。
     * @param target 文件输出目标
     * @param context 发起导出的可信用户上下文
     * @param tenantId 用量所属租户
     * @param type 导出类型
     * @param granularity 时间粒度
     * @param from 查询起点
     * @param to 查询终点
     * @param userId 可选成员过滤条件
     * @param apiKeyId 可选密钥过滤条件
     * @param serviceId 可选模型服务过滤条件
     * @param dimension 维度排行的分组维度
     * @param orderBy 维度排行的排序口径
     * @param limit 维度排行的最大返回项数
     */
    void export(UsageExportTarget target, UserContext context, Long tenantId, UsageExportType type,
                UsageGranularity granularity, LocalDateTime from, LocalDateTime to, Long userId,
                Long apiKeyId, Long serviceId, UsageBreakdownDimension dimension,
                UsageBreakdownOrder orderBy, int limit);
}
