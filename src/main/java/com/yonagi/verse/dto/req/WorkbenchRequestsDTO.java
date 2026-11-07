package com.yonagi.verse.dto.req;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.yonagi.verse.common.web.WorkbenchRequestDecoders;
import com.yonagi.verse.dto.playground.WorkbenchTypes.ConfigDTO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.Set;

/** 工作台命令载荷；专用 Web 反序列化保持类型白名单与数字精度。 */
public final class WorkbenchRequestsDTO {
    private WorkbenchRequestsDTO() {
    }

    /** 创建会话或预设。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    @JsonDeserialize(using = WorkbenchRequestDecoders.Create.class)
    public static class CreateReqDTO {
        /** 资源标题。 */ private String title;
        /** 资源描述。 */ private String description;
        /** 不含历史的完整配置。 */ private ConfigDTO config;
    }

    /** 携带修订号的局部更新。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    @JsonDeserialize(using = WorkbenchRequestDecoders.Update.class)
    public static class UpdateReqDTO {
        /** 可选标题。 */ private String title;
        /** 可选描述，显式 null 表示清空。 */ private String description;
        /** 可选完整配置。 */ private ConfigDTO config;
        /** 客户端看到的当前修订号。 */ private BigDecimal revision;
        /** Web 绑定记录的实际字段，保留未提供与显式 null 差异。 */
        @JsonIgnore private Set<String> suppliedFields;
    }

    /** 从服务端历史分叉。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    @JsonDeserialize(using = WorkbenchRequestDecoders.Fork.class)
    public static class ForkReqDTO {
        /** 可选分叉尝试标识。 */ private String attemptId;
        /** 未指定尝试时的来源栏位。 */ private String laneId;
        /** 是否排除指定问题及其回答。 */ private Boolean before;
        /** 可选的新会话配置。 */ private ConfigDTO config;
        /** 可选的新标题。 */ private String title;
    }

    /** 启动下一轮。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    @JsonDeserialize(using = WorkbenchRequestDecoders.Round.class)
    public static class RoundReqDTO {
        /** 用户问题，历史由服务端构建。 */ private String prompt;
    }

    /** 恢复预设历史版本。 */
    @Data @NoArgsConstructor @AllArgsConstructor
    @JsonDeserialize(using = WorkbenchRequestDecoders.Restore.class)
    public static class RestoreReqDTO {
        /** int 范围内的精确正整数版本。 */ private BigDecimal version;
    }
}
