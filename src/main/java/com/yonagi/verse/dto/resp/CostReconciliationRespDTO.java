package com.yonagi.verse.dto.resp;

import java.util.List;

public final class CostReconciliationRespDTO {
    private CostReconciliationRespDTO() {}

    public record Batch(
            /** 本批次定位码。 */ String code,
            /** 首次发现轮次。 */ String scanId,
            /** 扫描时间，北京时间。 */ String scannedAt,
            /** 本租户的待补账数量。 */ long pendingCount,
            /** 本租户的已处理数量。 */ long resolvedCount,
            /** 下一页游标。 */ String nextCursor,
            /** 该状态下的本页案件。 */ List<Case> items) {}

    public record Case(
            /** 案件标识。 */ String id,
            /** Verse 请求标识。 */ String requestId,
            /** 当时使用的模型标识。 */ String serviceId,
            /** 模型名称或历史标识。 */ String model,
            /** API Key 或 Playground 来源。 */ String source,
            /** 原请求发生时间，北京时间。 */ String requestedAt,
            /** 案件当前状态。 */ String state,
            /** 首次发现差异的原因。 */ String reason,
            /** 用于并发校验的案件版本。 */ long version,
            /** 已处理结果有新证据待运维复核。 */ boolean reviewRequired,
            /** 请求发生时的价格。 */ Price price,
            /** 结案后有效的费用结果。 */ Resolution resolution) {}

    public record Price(
            /** REQUEST 或 TOKEN。 */ String mode,
            /** 单次价格，分。 */ String requestFen,
            /** 每百万未命中输入 Token，分。 */ String inputFen,
            /** 每百万命中输入 Token，分。 */ String cachedFen,
            /** 每百万输出 Token，分。 */ String outputFen) {}

    public record Resolution(
            /** 有效费用分类。 */ String status,
            /** 按原定价确认的金额，分。 */ String costFen,
            /** 自动结案或管理员处理。 */ String method,
            /** 处理时刻，北京时间。 */ String resolvedAt,
            /** 用于结案的上游输入 Token。 */ Long inputTokens,
            /** 用于结案的上游缓存命中 Token。 */ Long cachedTokens,
            /** 用于结案的上游输出 Token。 */ Long outputTokens) {}
}
