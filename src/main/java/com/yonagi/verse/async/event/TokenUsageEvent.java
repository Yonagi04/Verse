package com.yonagi.verse.async.event;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEvent;
import com.yonagi.verse.service.pricing.CostResult;
import com.yonagi.verse.service.pricing.PricingSnapshot;
import com.yonagi.verse.service.usage.UsageBreakdown;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Token 消耗事件 — 转发成功后由生产者投递，消费者异步落 t_token_usage。
 *
 * @author Yonagi
 */
@Getter
@Setter
@NoArgsConstructor
public class TokenUsageEvent extends DomainEvent {

    public static final String SOURCE_EXACT = "EXACT";
    public static final String SOURCE_ESTIMATED = "ESTIMATED";
    public static final String SOURCE_UNKNOWN = "UNKNOWN";

    /** 用户 ID。 */
    private Long userId;

    /** 租户 ID。 */
    private Long tenantId;

    /** API Key ID。 */
    private Long apiKeyId;

    /** 服务 ID。 */
    private Long serviceId;

    /**
     * 实际调用的模型别名
     */
    private String model;

    /** 客户端操作，旧事件默认为 Chat Completions。 */
    private String operation = "CHAT_COMPLETIONS";

    /** 实际生成的图片数量；未知时为空。 */
    private Integer imageCount;

    /** 实际音频时长，毫秒；未知时为空。 */
    private Long audioDurationMs;

    /** 实际参与重排的文档数；未知时为空。 */
    private Integer rerankDocumentCount;

    /** 原始输入 Token 数。 */
    private Integer promptTokens;

    /** 原始输出 Token 数。 */
    private Integer completionTokens;

    /** 原始总 Token 数。 */
    private Integer totalTokens;

    /** 请求追踪 ID。 */
    private String requestId;

    /**
     * 状态：SUCCESS / ABORTED / FAIL
     */
    private String status;

    /**
     * usage来源：EXACT / ESTIMATED / UNKNOWN
     */
    private String usageSource;

    /** 网关请求开始时间，用于选择对应定价版本。 */
    private Instant requestStartedAt;

    /** 标准化后的用量。 */
    private UsageBreakdown normalizedUsage;

    /** 命中的定价快照。 */
    private PricingSnapshot pricingSnapshot;

    /** 费用计算结果。 */
    private CostResult costResult;

    /** 原始用量 JSON，不包含提示词或模型响应正文。 */
    private String usageDetailsJson;

    @Override
    public String eventType() {
        return EventTag.TOKEN_USAGE;
    }
}
