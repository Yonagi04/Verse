package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.service.usage.UsageBreakdown;

/** 接收侧确认成功后冻结的内部证据，不进入公共事件或改变原结算指纹。 */
public record UpstreamCompletionProof(
        /** 本次物理尝试 ID。 */
        Long attemptId,
        /** 原始请求 ID。 */
        String requestId,
        /** 实际执行服务 ID。 */
        Long serviceId,
        /** 本次模型操作。 */
        String operation,
        /** 固化用量，隔离解析器中可变的原始缓存 Token 证据。 */
        String usageJson) {

    public static UpstreamCompletionProof capture(Long attemptId, String requestId, Long serviceId,
                                                   ModelOperation operation, UsageBreakdown usage) {
        return new UpstreamCompletionProof(attemptId, requestId, serviceId, operation.name(), JSON.toJSONString(usage));
    }

    public UsageBreakdown usage() {
        return JSON.parseObject(usageJson, UsageBreakdown.class);
    }
}
