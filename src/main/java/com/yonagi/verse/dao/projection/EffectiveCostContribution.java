package com.yonagi.verse.dao.projection;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/** 原始结算或其当前有效更正。 */
@Data
public class EffectiveCostContribution {
    /** 原用量事件标识，作为跨版本逻辑身份。 */
    private String eventId;
    /** 原调用租户。 */
    private Long tenantId;
    /** 原调用用户。 */
    private Long userId;
    /** 原调用 Key，Playground 为空。 */
    private Long apiKeyId;
    /** 当前费用归属的实际模型。 */
    private Long serviceId;
    /** 原调用来源。 */
    private String source;
    /** 当前费用归属的上游操作。 */
    private String operation;
    /** 原调用标识。 */
    private String requestId;
    /** 原调用开始时间。 */
    private LocalDateTime requestStartedAt;
    /** 当前费用分类。 */
    private String costStatus;
    /** 费用金额，分。 */
    private BigDecimal costFen;
    /** 该贡献是否已应用到预算。 */
    private Boolean budgetApplied;
    /** 原快照为零，更正使用其不可变版本。 */
    private Long contributionVersion;

    /** 使用上页最后一条事实的原请求时间及事件标识。 */
    public record Cursor(LocalDateTime requestStartedAt, String eventId) {
        public Cursor {
            Objects.requireNonNull(requestStartedAt, "requestStartedAt");
            Objects.requireNonNull(eventId, "eventId");
        }
    }
}
