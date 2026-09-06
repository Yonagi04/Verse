package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.enums.UsageOutboxStatus;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import com.yonagi.verse.dao.mapper.TokenUsageCostMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dto.resp.UsageEventReconciliationRespDTO;
import com.yonagi.verse.service.UsageEventOperationsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 租户管理员使用的对账与人工重放实现。 */
@Service
@RequiredArgsConstructor
public class UsageEventOperationsServiceImpl implements UsageEventOperationsService {
    private final TokenUsageOutboxMapper outboxMapper;
    private final UserTenantMapper userTenantMapper;
    private final TokenUsageCostMapper tokenUsageCostMapper;

    @Override
    public UsageEventReconciliationRespDTO reconcile(Long userId, Long tenantId) {
        validateMembership(userId, tenantId);
        UsageEventReconciliationRespDTO result = new UsageEventReconciliationRespDTO();
        result.setPendingCount(outboxMapper.countTenantStatus(tenantId, UsageOutboxStatus.PENDING.name())
                + outboxMapper.countTenantStatus(tenantId, UsageOutboxStatus.CLAIMED.name()));
        result.setRetryCount(outboxMapper.countTenantStatus(tenantId, UsageOutboxStatus.RETRY.name()));
        result.setFailedCount(outboxMapper.countTenantStatus(tenantId, UsageOutboxStatus.FAILED.name()));
        result.setPublishedMissingFactCount(outboxMapper.countTenantPublishedMissingFacts(tenantId));
        result.setUsageMissingCostCount(tokenUsageCostMapper == null ? 0L : tokenUsageCostMapper.countMissingByTenantId(tenantId));
        return result;
    }

    @Override
    public boolean replay(Long userId, Long tenantId, String eventId) {
        validateMembership(userId, tenantId);
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId 不能为空");
        }
        return outboxMapper.resetForReplay(tenantId, eventId, LocalDateTime.now()) == 1;
    }

    private void validateMembership(Long userId, Long tenantId) {
        Long count = userTenantMapper.selectCount(Wrappers.lambdaQuery(UserTenantDO.class)
                .eq(UserTenantDO::getUserId, userId)
                .eq(UserTenantDO::getTenantId, tenantId));
        if (count == null || count == 0) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        }
    }
}
