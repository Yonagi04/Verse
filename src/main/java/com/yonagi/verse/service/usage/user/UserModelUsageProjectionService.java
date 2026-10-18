package com.yonagi.verse.service.usage.user;

import com.yonagi.verse.service.user.costalert.UserCostAlertTriggerService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.config.UserModelUsageProperties;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.*;
import java.util.*;

/** 同用户的调用去重与日汇总在同一事务内完成；失败交给原工作项重试。 */
@Service @RequiredArgsConstructor
public class UserModelUsageProjectionService {
    private final UserModelUsageProperties properties;
    private final UserModelUsageAccessPolicy access;
    private final TokenUsageMapper facts;
    private final TokenUsageCostMapper costs;
    private final UserModelUsageProjectionMapper work;
    private final UserModelUsageDailyMapper daily;
    private final UserCostAlertTriggerService capture;
    private final BudgetPeriodResolver time;

    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void enqueue(Long userId,Long usageId) {
        if (!properties.isProjectionEnabled()) return;
        var usage=facts.selectById(usageId);
        if (usage==null || !userId.equals(usage.getUserId())) throw new IllegalStateException("USAGE_OWNER_MISMATCH");
        if (find(usage.getUserId(),usage.getId())!=null) return;
        boolean valid=usage.getRequestId()!=null && !usage.getRequestId().isBlank() && usage.getRequestStartedAt()!=null
                && usage.getTenantId()!=null && usage.getSource()!=null && List.of("API_KEY","PLAYGROUND").contains(usage.getSource());
        String key=valid ? cn.hutool.crypto.digest.DigestUtil.sha256Hex(usage.getSource()+":"+usage.getTenantId()+":"+usage.getRequestId()) : null;
        var canonical=key==null ? null : work.selectOne(Wrappers.lambdaQuery(UserModelUsageProjectionDO.class)
                .eq(UserModelUsageProjectionDO::getUserId,usage.getUserId()).eq(UserModelUsageProjectionDO::getLogicalKey,key));
        var row=new UserModelUsageProjectionDO(); row.setUserId(usage.getUserId()); row.setUsageId(usage.getId());
        row.setSource(usage.getSource()); row.setTenantId(usage.getTenantId()); row.setRequestId(usage.getRequestId());
        row.setLogicalKey(canonical==null ? key : null); row.setCanonicalUsageId(canonical==null ? usage.getId() : canonical.getUsageId());
        row.setStatus(valid ? "PENDING" : "FAILED"); row.setLastErrorCode(valid ? null : "IDENTITY_INCOMPLETE"); row.setAttemptCount(0);
        row.setCreateTime(time.local(time.now())); row.setNextRetryAt(row.getCreateTime()); work.insert(row);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void repair(Long userId,Long usageId) { access.lock(userId); enqueue(userId,usageId); }

    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void project(Long userId,Long usageId) {
        access.lock(userId);
        var row=find(userId,usageId);
        if (row==null || !"PENDING".equals(row.getStatus()) || row.getNextRetryAt().isAfter(time.local(time.now()))) return;
        var usage=facts.selectById(usageId); var cost=cost(usageId);
        if (usage==null || !userId.equals(usage.getUserId()) || usage.getRequestStartedAt()==null) throw new IllegalStateException("FACT_MISSING");
        if (!usageId.equals(row.getCanonicalUsageId())) {
            var canonical=facts.selectById(row.getCanonicalUsageId());
            if (canonical==null || !UserModelUsageContributionPolicy.equivalent(usage,cost,canonical,cost(row.getCanonicalUsageId()))) {
                row.setStatus("FAILED"); row.setLastErrorCode("LOGICAL_CONFLICT"); work.finish(row); return;
            }
            var first=find(userId,row.getCanonicalUsageId());
            if (first==null || !"APPLIED".equals(first.getStatus())) throw new IllegalStateException("CANONICAL_PENDING");
            row.setStatus("DUPLICATE");
        } else {
            var contribution=UserModelUsageContributionPolicy.contribution(usage,cost);
            var existing=daily.selectOne(Wrappers.lambdaQuery(UserModelUsageDailyDO.class).eq(UserModelUsageDailyDO::getUserId,userId)
                    .eq(UserModelUsageDailyDO::getUsageDate,usage.getRequestStartedAt().toLocalDate()));
            UserModelUsageContributionPolicy.checkTotal(contribution.cost().add(existing==null ? java.math.BigDecimal.ZERO : existing.getKnownCostFen()));
            if (existing!=null) { Math.addExact(existing.getSuccessCount(),contribution.successes()); Math.addExact(existing.getUnknownCostCount(),contribution.unknown()); }
            var day=new UserModelUsageDailyDO(); day.setUserId(userId); day.setUsageDate(usage.getRequestStartedAt().toLocalDate());
            day.setSuccessCount(contribution.successes()); day.setKnownCostFen(contribution.cost()); day.setUnknownCostCount(contribution.unknown());
            day.setUpdatedAt(time.local(time.now())); daily.contribute(day); row.setStatus("APPLIED");
        }
        row.setProjectedAt(time.local(time.now())); work.finish(row);
        capture.capture(userId);
    }
    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public void failed(Long userId,Long usageId,String error) {
        access.lock(userId); var row=find(userId,usageId);
        if (row==null || !"PENDING".equals(row.getStatus())) return;
        row.setAttemptCount(row.getAttemptCount()+1); row.setLastErrorCode(error);
        row.setStatus(row.getAttemptCount()>=10 ? "FAILED" : "PENDING");
        row.setNextRetryAt(time.local(time.now()).plusSeconds(Math.min(300,1L<<Math.min(8,row.getAttemptCount())))); work.finish(row);
    }
    public UserModelUsageProjectionDO find(Long userId,Long usageId) {
        return work.selectOne(Wrappers.lambdaQuery(UserModelUsageProjectionDO.class).eq(UserModelUsageProjectionDO::getUserId,userId)
                .eq(UserModelUsageProjectionDO::getUsageId,usageId));
    }
    private TokenUsageCostDO cost(Long usageId) {
        return costs.selectOne(Wrappers.lambdaQuery(TokenUsageCostDO.class).eq(TokenUsageCostDO::getUsageId,usageId));
    }
}
