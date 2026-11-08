package com.yonagi.verse.service.impl;

import com.yonagi.verse.dao.mapper.TokenUsageHourlyAggMapper;
import com.yonagi.verse.service.UsageProjectionService;
import com.yonagi.verse.service.budget.CostReconciliationTracking;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** 用量小时投影实现，删除并重建绝对值以保证幂等。 */
@Service
@RequiredArgsConstructor
public class UsageProjectionServiceImpl implements UsageProjectionService {
    private final TokenUsageHourlyAggMapper mapper;
    private CostReconciliationTracking reconciliation;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReconciliationTracking(CostReconciliationTracking tracking) {
        this.reconciliation = tracking;
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void rebuild(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("用量投影时间范围不合法");
        }
        if (mapper.lockProjection() == null) throw new IllegalStateException("缺少统计投影锁初始化行");
        mapper.deleteRange(from, to);
        if (reconciliation != null && reconciliation.enabled()) {
            mapper.rebuildEffectiveRange(null, from, to);
        } else {
            mapper.rebuildRange(from, to);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void rebuildTenantHour(Long tenantId, LocalDateTime hour) {
        if (tenantId == null || hour == null || !hour.equals(hour.truncatedTo(ChronoUnit.HOURS))) {
            throw new IllegalArgumentException("租户及整点小时不能为空");
        }
        if (mapper.lockProjection() == null) {
            throw new IllegalStateException("缺少统计投影锁初始化行");
        }
        mapper.deleteTenantHour(tenantId, hour);
        mapper.rebuildEffectiveRange(tenantId, hour, hour.plusHours(1));
    }
}
