package com.yonagi.verse.dto.resp;

import com.alibaba.fastjson2.annotation.JSONField;

import java.util.List;

/** 兼容协议的预算错误详情，预算不可用时省略限额字段。 */
public record OpenAiCostErrorDetailDTO(
        /** 可对客户端展示的错误说明。 */ String message,
        /** Verse 业务错误码。 */ @JSONField(name = "business_code") String businessCode,
        /** 关联当前请求的追踪标识。 */ @JSONField(name = "request_id") String requestId,
        /** OpenAI 兼容错误类别。 */ String type,
        /** 客户端识别预算错误的代码。 */ String code,
        /** 首个命中的限额范围。 */ String scope,
        /** 首个命中的限额周期。 */ String period,
        /** 所有命中周期中最晚的恢复时间。 */ @JSONField(name = "retry_at") String retryAt,
        /** 限额周期使用的时区。 */ String timezone,
        /** 命中的限额周期及结束时间。 */ List<LimitDTO> limits) {

    /** 单个命中的限额周期。 */
    public record LimitDTO(
            /** 限额适用的范围。 */ String scope,
            /** 达到限额的周期。 */ String period,
            /** 当前周期的结束时间。 */ @JSONField(name = "period_end") String periodEnd) { }
}
