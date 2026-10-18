package com.yonagi.verse.dto.resp;

import java.util.List;

/** 本人模型成功调用的日历视图，与费用提醒设置独立。 */
public record UserModelActivityCalendarRespDTO(
            /** 范围模式。 */ String view,
            /** 自然年模式的年份。 */ Integer year,
            /** 固定北京时间。 */ String timezone,
            /** 北京今天。 */ String today,
            /** 区间开始，含。 */ String rangeStart,
            /** 区间结束，含。 */ String rangeEnd,
            /** 已知成功次数总计。 */ String totalSuccessCount,
            /** 读模型可用状态。 */ String availability,
            /** 查询生成时间。 */ String asOf,
            /** 当前范围最近汇总时间。 */ String updatedAt,
            /** 最老待办年龄秒。 */ Long lagSeconds,
            /** 兼容字段：范围内已入库调用是否均已处理，不认证历史采集覆盖。 */ boolean historyComplete,
            /** 兼容字段：合并后的调用处理状态区间，不依赖覆盖证明表。 */ List<Coverage> coverage,
            /** 逐日数据。 */ List<Day> days) {
    public record Day(
            /** 北京日期。 */ String date,
            /** 已知成功次数，未知或未来为空。 */ String successCount,
            /** COMPLETE/PARTIAL/MISSING/FUTURE。 */ String state) { }
    public record Coverage(
            /** 起点日期，含。 */ String from,
            /** 终点日期，含。 */ String to,
            /** 事实质量。 */ String state,
            /** 原因。 */ String reasonCode) { }
}
