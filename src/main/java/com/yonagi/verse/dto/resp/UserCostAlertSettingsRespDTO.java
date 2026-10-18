package com.yonagi.verse.dto.resp;

import java.util.List;

/** 用户费用预警设置、当前进度和本人渠道准备度。 */
public record UserCostAlertSettingsRespDTO(
            /** 正式配置。 */ Settings config,
            /** 人民币。 */ String currency,
            /** 固定北京时间。 */ String timezone,
            /** 北京今天。 */ String today,
            /** 查询生成时间。 */ String asOf,
            /** 读模型可用状态。 */ String availability,
            /** 当前范围最近汇总时间。 */ String updatedAt,
            /** 最老待办年龄秒。 */ Long lagSeconds,
            /** 固定日周两项。 */ List<PeriodUsage> periods,
            /** 三渠道准备度。 */ List<ChannelReadiness> readiness) {
    public record PeriodSettings(
            /** 是否启用。 */ boolean enabled,
            /** 阈值整数分。 */ String thresholdFen) { }
    public record Settings(
            /** 配置版本。 */ String version,
            /** 日设置。 */ PeriodSettings daily,
            /** 周设置。 */ PeriodSettings weekly,
            /** 共用渠道。 */ List<String> channels) { }
    public record ChannelReadiness(
            /** 渠道。 */ String channel,
            /** 是否具备配置条件。 */ boolean available,
            /** 不可用原因。 */ String reasonCode,
            /** 本人脱敏联系方式。 */ String contactMasked) { }
    public record PeriodUsage(
            /** DAY 或 WEEK。 */ String period,
            /** 起点，含。 */ String periodStart,
            /** 终点，不含。 */ String periodEnd,
            /** 是否开启。 */ boolean enabled,
            /** 阈值整数分。 */ String thresholdFen,
            /** 原始精度的已知累计分。 */ String knownCostFen,
            /** 未知费用调用数。 */ String unknownCostCount,
            /** 已入库调用是否均已处理且费用可确定。 */ boolean costComplete,
            /** 业务预警状态，不代表送达。 */ String alertState,
            /** 生成预警时间。 */ String triggeredAt) { }
}
