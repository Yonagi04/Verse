package com.yonagi.verse.dto.resp;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.util.List;

/** 已加入租户的只读摘要响应。 */
public final class TenantOverviewRespDTO {
    private TenantOverviewRespDTO() { }

    @Data
    public static class Batch {
        /** 近三十天窗口起点，上海时区 ISO 8601。 */ private String from;
        /** 近三十天窗口终点，不包含该时刻。 */ private String to;
        /** 用量投影最近更新时间；无投影记录时为空。 */ private String updatedAt;
        /** 用量投影配置延迟分钟数。 */ private Integer dataDelayMinutes;
        /** 当前用户有效租户的摘要。 */ private List<Item> items;
    }

    @Data
    public static class Item {
        /** 租户业务 ID。 */
        @JsonSerialize(using = ToStringSerializer.class)
        private Long tenantId;
        /** 有效成员数；查询失败时为空。 */ private Long memberCount;
        /** 已启用模型服务数；查询失败时为空。 */ private Long availableServiceCount;
        /** 按目标租户角色裁剪的近三十天用量。 */ private Usage usage;
        /** 管理员待审批加入申请数；无权限或查询失败时为空。 */ private Long pendingJoinRequestCount;
    }

    @Data
    public static class Detail extends Item {
        /** 近三十天窗口起点，上海时区 ISO 8601。 */ private String from;
        /** 近三十天窗口终点，不包含该时刻。 */ private String to;
        /** 用量投影最近更新时间；无投影记录时为空。 */ private String updatedAt;
        /** 用量投影配置延迟分钟数。 */ private Integer dataDelayMinutes;
        /** 最多三条脱敏动态；关闭记录或查询失败时为空。 */ private List<Activity> recentActivities;
    }

    @Data
    public static class Usage {
        /** TENANT 为目标租户汇总，SELF 为当前用户本人。 */ private String scope;
        /** Token 总量；全部证据未知时为空。 */ private String totalTokens;
        /** 成功请求次数。 */ private String requestCount;
        /** COMPLETE 或 PARTIAL。 */ private String tokenQuality;
    }

    @Data
    public static class Activity {
        /** 白名单内的动态类型。 */ private String type;
        /** 服务端固定中文标题。 */ private String title;
        /** 上海时区 ISO 8601 发生时间。 */ private String occurredAt;
    }
}
