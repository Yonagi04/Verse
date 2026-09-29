package com.yonagi.verse.dao.projection;

import lombok.Data;
import java.time.LocalDateTime;

/** 不包含参与人、目标名称和详情的脱敏动态投影。 */
@Data
public class TenantOverviewActivityRow {
    /** 白名单内的动态类型。 */ private String type;
    /** 动态发生时间。 */ private LocalDateTime occurredAt;
}
