package com.yonagi.verse.resilience.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.resilience.api.FallbackExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 降级执行实现 — 按 {@code fallbackServiceId} 解析租户内启用的备用模型。
 * 备用模型的转发、限流、熔断由编排层复用完整韧性流程完成。
 *
 * @author Yonagi
 */
@Component
@RequiredArgsConstructor
public class FallbackExecutorImpl implements FallbackExecutor {

    private final LlmServiceMapper llmServiceMapper;

    @Override
    public LlmServiceDO resolveFallback(LlmServiceDO primary) {
        if (primary == null || primary.getFallbackServiceId() == null) {
            return null;
        }
        return llmServiceMapper.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getServiceId, primary.getFallbackServiceId())
                .eq(LlmServiceDO::getTenantId, primary.getTenantId())
                .eq(LlmServiceDO::getStatus, 1)
                .eq(LlmServiceDO::getDelFlag, 0));
    }
}
