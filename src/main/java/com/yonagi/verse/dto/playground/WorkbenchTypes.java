package com.yonagi.verse.dto.playground;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yonagi.verse.common.enums.BillingMode;
import com.yonagi.verse.common.enums.PricePeriodType;
import com.yonagi.verse.dto.resp.ApiKeyCostStatusRespDTO.Hit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.math.BigDecimal;
import java.util.List;

/** 工作台配置、持久化快照和返回视图；业务标识保持字符串协议，正文不进入字符串表示。 */
public final class WorkbenchTypes {
    private WorkbenchTypes() {
    }

    /** 可用参数的闭区间。 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class RangeVO {
        /** 最小可用值。 */ private Double min;
        /** 最大可用值。 */ private Double max;
    }

    /** 模型显式声明并经过适配器约束的能力。 */
    @Data @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CapabilitiesVO {
        /** 是否支持系统提示词。 */ private Boolean system;
        /** 温度范围；未声明时不支持。 */ private RangeVO temperature;
        /** 核采样范围；未声明时不支持。 */ private RangeVO topP;
        /** 工作台独立输出上限。 */ private Long maxTokens;
    }

    /** 单栏运行配置；数值保留十进制精度，Token 整数性由业务规则验证。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RunConfigDTO {
        /** 系统提示词。 */ private String system;
        /** 采样温度。 */ private BigDecimal temperature;
        /** 核采样概率。 */ private BigDecimal topP;
        /** 请求输出 Token 数。 */ private BigDecimal maxTokens;
    }

    /** 可独立执行的稳定栏位。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    public static class LaneDTO {
        /** 栏位 UUID。 */ private String laneId;
        /** 租户模型业务标识。 */ private String serviceId;
        /** 当前栏的白名单配置。 */ private RunConfigDTO config;
    }

    /** 可编辑配置，不允许客户端提供对话历史。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConfigDTO {
        /** 一至三个有序栏位。 */ private List<LaneDTO> lanes;
        /** 是否共享完全相同的运行配置。 */ private Boolean synced;
        /** 来源预设标识。 */ private String presetId;
        /** 来源预设版本快照。 */ private BigDecimal presetVersion;
    }

    /** 服务端构建的对话消息。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    public static class MessageDTO {
        /** 上游协议角色。 */ private String role;
        /** 消息正文。 */ private String content;
    }

    /** 追加式预设版本。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    public static class VersionDTO {
        /** 创建该配置时的修订号。 */ private Integer number;
        /** 版本创建时间，含时区。 */ private String createdAt;
        /** 当时的完整可编辑配置。 */ private ConfigDTO config;
    }

    /** 分叉来源的展示快照。 */
    @Data @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
    @ToString(onlyExplicitlyIncluded = true)
    public static class SourceDTO {
        /** 原会话标识。 */ private String groupId;
        /** 原会话标题。 */ private String title;
        /** 分叉截止轮次；末尾分叉为空。 */ private Integer roundNo;
        /** 原栏位展示名称。 */ private String laneLabel;
    }

    /** 兼容历史 JSON 的工作区载荷；会话保存配置与前缀，预设保存版本。 */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor
    @ToString(onlyExplicitlyIncluded = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PayloadDTO {
        /** 会话栏位。 */ private List<LaneDTO> lanes;
        /** 会话共享配置标志。 */ private Boolean synced;
        /** 来源预设标识。 */ private String presetId;
        /** 来源预设版本。 */ private BigDecimal presetVersion;
        /** 分叉时冻结的服务端历史消息。 */ private List<MessageDTO> prefix;
        /** 分叉来源快照。 */ private SourceDTO source;
        /** 预设不可变版本列表。 */ private List<VersionDTO> versions;
    }

    /** 每次尝试冻结的上游请求；蛇形字段仅用于原协议快照。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RequestDTO {
        /** 租户内模型别名。 */ private String model;
        /** 服务端构建的消息序列。 */ private List<MessageDTO> messages;
        /** 是否使用流式上游协议。 */ private Boolean stream;
        /** 采样温度。 */ private BigDecimal temperature;
        /** 核采样概率。 */ @JsonProperty("top_p") private BigDecimal topP;
        /** 输出上限或尚未通过预检的原始输入。 */ @JsonProperty("max_tokens") private BigDecimal maxTokens;
    }

    /** 重试必须沿用的原始请求与配置，不随当前工作区修改。 */
    @Data @Builder(toBuilder = true) @NoArgsConstructor @AllArgsConstructor
    @ToString(onlyExplicitlyIncluded = true) @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SnapshotDTO {
        /** 当次冻结的上游请求。 */ private RequestDTO request;
        /** 当次运行配置。 */ private RunConfigDTO config;
        /** 当次展示模型名。 */ private String modelName;
        /** 当次供应商标识。 */ private String provider;
        /** 当次有效模型能力。 */ private CapabilitiesVO capabilities;
        /** 来源预设标识。 */ private String presetId;
        /** 来源预设版本。 */ private BigDecimal presetVersion;
        /** 重试请求幂等键。 */ private String retryKey;
        /** 被重试的原尝试标识。 */ private String retrySource;
    }

    /** 不含金额与接收人的费用拒绝详情。 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ErrorDataVO {
        /** 首要命中的预算范围。 */ private String scope;
        /** 首要命中的预算周期。 */ private String period;
        /** 可重试时间。 */ private String retryAt;
        /** 预算采用的业务时区。 */ private String timezone;
        /** 已脱敏的命中项。 */ private List<Hit> limits;
        /** 当前请求标识。 */ private String requestId;
    }

    /** 对客户端稳定且脱敏的尝试错误。 */
    @Data @NoArgsConstructor @AllArgsConstructor @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ErrorVO {
        /** 稳定业务错误码。 */ private String code;
        /** 安全文案。 */ private String message;
        /** 可展示的费用拒绝详情。 */ private ErrorDataVO data;
        /** 访问保护拒绝原因。 */ private String reason;
        /** 建议等待的秒数。 */ private Long retryAfterSeconds;
        /** 历史错误中可选的 HTTP 状态。 */ private Integer httpStatus;
    }

    /** 单次尝试的用量、结算与延迟视图。 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class MetricsVO {
        /** 输入 Token 数。 */ private Long inputTokens;
        /** 输出 Token 数。 */ private Long outputTokens;
        /** 用量证据类型。 */ private String evidence;
        /** 费用结算状态。 */ private String costStatus;
        /** 费用，单位分。 */ private BigDecimal costFen;
        /** 计价币种。 */ private String currency;
        /** 首段正文延迟毫秒。 */ private Long firstContentMs;
        /** 总耗时毫秒。 */ private Long durationMs;
    }

    /** 按当前请求时间解析的价格。 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class PricingVO {
        /** 币种。 */ private String currency;
        /** 计费方式。 */ private BillingMode billingMode;
        /** 未命中缓存的输入单价，单位分。 */ private BigDecimal inputPriceFen;
        /** 输出单价，单位分。 */ private BigDecimal outputPriceFen;
        /** 单请求价格，单位分。 */ private BigDecimal requestPriceFen;
        /** 定价时段类型。 */ private PricePeriodType periodType;
    }

    /** 工作台可用模型；缓存元数据时不固定价格。 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class ModelVO {
        /** 模型业务标识。 */ private String serviceId;
        /** 租户内模型名。 */ private String name;
        /** 供应商标识。 */ private String provider;
        /** 模型说明。 */ private String description;
        /** 上下文 Token 上限。 */ private Long contextWindow;
        /** 有效工作台能力。 */ private CapabilitiesVO capabilities;
        /** 当前价格；未配置时为空。 */ private PricingVO pricing;
    }

    /** 一次栏位调用的完整私有视图。 */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    public static class AttemptVO {
        /** 尝试业务标识。 */ private String attemptId;
        /** 所属会话标识。 */ private String groupId;
        /** 轮次幂等标识。 */ private String roundId;
        /** 会话内轮次序号。 */ private Integer roundNo;
        /** 稳定栏位标识。 */ private String laneId;
        /** 同栏同轮的尝试序号。 */ private Integer attemptNo;
        /** 转发请求标识。 */ private String requestId;
        /** 模型业务标识。 */ private String serviceId;
        /** 用户问题。 */ private String prompt;
        /** 已保存回复。 */ private String reply;
        /** 调用状态。 */ private String status;
        /** 原始执行快照。 */ private SnapshotDTO snapshot;
        /** 安全错误。 */ private ErrorVO error;
        /** 用量与耗时。 */ private MetricsVO metrics;
        /** 创建时间，含时区。 */ private String createdAt;
        /** 终态时间，含时区。 */ private String finishedAt;
    }

    /** 会话或预设的成员私有资源视图。 */
    @Data @Builder @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    public static class WorkspaceVO {
        /** 工作区业务标识。 */ private String id;
        /** 标题。 */ private String title;
        /** 描述。 */ private String description;
        /** GROUP 或 PRESET。 */ private String kind;
        /** 可编辑配置、来源或版本历史。 */ private PayloadDTO payload;
        /** 乐观更新修订号。 */ private Integer revision;
        /** 是否仍有活跃栏位。 */ private Boolean generating;
        /** 创建时间，含时区。 */ private String createdAt;
        /** 更新时间，含时区。 */ private String updatedAt;
        /** 会话详情中的尝试列表。 */
        @JsonInclude(JsonInclude.Include.NON_NULL) private List<AttemptVO> attempts;
        /** 分叉原资源是否仍可访问。 */
        @JsonInclude(JsonInclude.Include.NON_NULL) private Boolean sourceAvailable;
    }

    /** 本轮接受的独立栏位尝试。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    public static class RoundVO {
        /** 轮次幂等标识。 */ private String roundId;
        /** 各栏独立尝试。 */ private List<AttemptVO> attempts;
    }

    /** SSE 接受事件。 */
    @Data @NoArgsConstructor @AllArgsConstructor
    public static class AcceptedVO {
        /** 尝试标识。 */ private String attemptId;
        /** 转发请求标识。 */ private String requestId;
    }

    /** SSE 正文增量。 */
    @Data @NoArgsConstructor @AllArgsConstructor @ToString(onlyExplicitlyIncluded = true)
    public static class DeltaVO {
        /** 尝试标识。 */ private String attemptId;
        /** 本次新增正文。 */ private String text;
    }
}
