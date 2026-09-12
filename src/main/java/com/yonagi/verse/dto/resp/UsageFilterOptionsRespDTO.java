package com.yonagi.verse.dto.resp;

import lombok.Data;
import java.util.List;

/** 用量报表筛选项响应。 */
@Data
public class UsageFilterOptionsRespDTO {
    /** 模型服务选项。 */ private List<Option> services;
    /** API Key 选项。 */ private List<Option> apiKeys;
    /** 成员选项；无全员权限时为空。 */ private List<Option> members;
    /** 是否允许查看成员维度。 */ private boolean canReadAll;

    /** 不含凭据的安全筛选项。 */
    @Data
    public static class Option {
        /** 业务 ID。 */ private String id;
        /** 展示名称。 */ private String label;
        public Option(String id, String label) { this.id=id; this.label=label; }
    }
}
