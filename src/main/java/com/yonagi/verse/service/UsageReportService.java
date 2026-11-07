package com.yonagi.verse.service;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.UsageDashboardRespDTO;
import com.yonagi.verse.dto.resp.UsageReportRespDTO;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.dto.resp.UsageFilterOptionsRespDTO;
import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.service.reporting.UsageReportFilter;
import java.time.LocalDateTime;

/** 用量报表查询服务。 */
public interface UsageReportService {
    /**
     * 查询租户用量时间序列，无 Key 或服务筛选。空时间段按指定粒度补零。
     *
     * @see #query(UserContext, Long, UsageGranularity, LocalDateTime, LocalDateTime, Long, Long, Long)
     */
    UsageReportRespDTO query(UserContext context, Long tenantId, UsageGranularity granularity,
                             LocalDateTime from, LocalDateTime to, Long userId);

    /**
     * 在授权范围内汇总 API Key 用量的小时投影，返回按指定粒度补齐的序列及全量合计。
     * 时间窗口左闭右开，缺失边界按粒度取默认值。普通成员仅能查询本人数据。
     * 可选成员、Key 和服务筛选须满足租户和所有权校验。
     *
     * @throws ClientException 访问被拒绝，或时间范围、筛选条件无效
     */
    UsageReportRespDTO query(UserContext context, Long tenantId, UsageGranularity granularity,
                             LocalDateTime from, LocalDateTime to, Long userId, Long apiKeyId, Long serviceId);

    /**
     * 返回租户仪表盘的当日汇总、最近 24 小时和最近 7 天图表，窗口按上海时区计算。
     *
     * @throws ClientException 当前主体不具备该租户的报表读取权限
     */
    UsageDashboardRespDTO dashboard(UserContext context, Long tenantId);

    /**
     * 在已授权的半开时间窗和筛选范围内返回指定维度与指标的前 1 至 100 组。
     * 成员维度仅对管理员开放。总计和每组占比按完整筛选范围计算。
     * 指标相同的分组按业务 ID、模型名顺序排列。
     *
     * @throws ClientException 访问被拒绝，或时间、筛选、行数无效
     */
    UsageBreakdownRespDTO breakdown(UserContext context, Long tenantId, UsageGranularity granularity,
        LocalDateTime from, LocalDateTime to, Long userId, Long apiKeyId, Long serviceId,
        UsageBreakdownDimension dimension, UsageBreakdownOrder order, int limit);

    /**
     * 返回该租户内当前主体可选择的模型、Key 和成员。普通成员只得到本人的 Key，成员选项为空。
     *
     * @throws ClientException 当前主体不具备该租户的报表读取权限
     */
    UsageFilterOptionsRespDTO filterOptions(UserContext context, Long tenantId);

    /**
     * 为查询和导出解析授权后的时间及维度筛选。空时间边界按粒度补齐。
     * 普通成员的有效用户 ID 始终为当前主体，管理员可按租户成员筛选。
     *
     * @return 可直接用于数据查询的有效筛选，空的可选维度表示该维度不限
     * @throws ClientException 访问被拒绝，或时间范围、筛选条件无效
     */
    UsageReportFilter resolveFilter(UserContext context, Long tenantId, UsageGranularity granularity,
        LocalDateTime from, LocalDateTime to, Long userId, Long apiKeyId, Long serviceId);
}
