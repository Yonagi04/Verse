package com.yonagi.verse.service.budget;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.TenantSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/** 整份设置的事务边界，权限锁在预算锁之前；读取和确认不缓存。 */
@Service @RequiredArgsConstructor
public class TenantCostBudgetConfigurationService {
    private final TenantMapper tenants;
    private final UserTenantMapper memberships;
    private final TenantCostBudgetMapper budgets;
    private final TenantCostBudgetLedger ledger;
    private final TenantSettingsService settings;
    private final TenantSettingsConfirmationService confirmation;
    private final BudgetPeriodResolver time;
    private final com.yonagi.verse.async.activity.TenantActivityRecorder activities;
    public static final String WARNING = "设置成本限额后，当租户在任一配置周期内的累计预估费用达到限额，该租户所有新的模型调用（包括 PlayGround）都将被限制，直到相关周期重置或管理员调整限额。已开始的调用可能继续产生费用。确定要继续吗？";

    private TenantDO lockTenant(Long userId, Long tenantId, boolean admin) {
        var tenant = tenants.selectActiveTenantForUpdate(tenantId);
        if (tenant == null || memberships.lockActiveJoiningUser(userId) == null) throw denied();
        var member = memberships.selectActiveMembershipForUpdate(userId, tenantId);
        if (member == null || admin && !RoleEnum.valueOf(member.getRole()).isAdmin()) throw denied();
        return tenant;
    }
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TenantCostStatusRespDTO status(Long userId, Long tenantId) {
        var tenant = lockTenant(userId, tenantId, false);
        var full = ledger.snapshot(ledger.lock(tenantId), String.valueOf(tenant.getSettingsVersion()));
        var role = memberships.selectActiveMembershipForUpdate(userId, tenantId).getRole();
        if (RoleEnum.valueOf(role).isAdmin()) return full;
        return new TenantCostStatusRespDTO.Summary(full.tenantId(), "SUMMARY", full.budgetState(), full.retryAt(), full.timezone(),
                full.asOf(), full.limits(), "LIMITED".equals(full.budgetState()) ? "此租户调用成本已达到限额，请联系管理员或等待相关周期重置"
                : "UNKNOWN".equals(full.budgetState()) ? "预算校验暂不可用，请稍后重试" : null);
    }
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public TenantSettingsSaveRespDTO save(Long userId, Long tenantId, TenantSettingsSaveReqDTO request) {
        var tenant = lockTenant(userId, tenantId, true);
        var current = ledger.lock(tenantId);
        var before = snapshot(userId, tenantId, current);
        var target = copy(current);
        merge(target, request.getCostLimit(), request.getCostWarning());
        normalize(request, tenant);
        boolean confirming = request.getConfirmationToken() != null;
        if (confirming && !explicitTarget(request)) throw new ClientException(CostBudgetErrorCodeEnum.CONFIRMATION_INVALID);
        String original = canonical(before.settings(), current), desired = canonical(request, target);
        boolean costChanged = !costCanonical(current).equals(costCanonical(target));
        boolean risk = needsConfirmation(current, target);
        var impact = risk ? ledger.preview(target) : new CostBudgetDecision(List.of(), false);
        String settingsVersion = String.valueOf(tenant.getSettingsVersion()), costVersion = String.valueOf(current.getConfigVersion());
        boolean proofValid = false;
        if (confirming) proofValid = confirmation.verify(request.getConfirmationToken(), userId, tenantId, settingsVersion,
                costVersion, desired, JSON.toJSONString(impact.limits()));
        // 响应丢失的原请求重交只能成为只读 no-op，不重复活动或版本。
        if (original.equals(desired)) return TenantSettingsSaveRespDTO.saved(before);
        if (!confirming) {
            if (!settingsVersion.equals(request.getSettingsVersion())) throw new ClientException(CostBudgetErrorCodeEnum.SETTINGS_VERSION_CONFLICT);
            if ((request.getCostLimit() != null || request.getCostWarning() != null) && (request.getCostLimit() == null
                    || !costVersion.equals(request.getCostLimit().getExpectedVersion()))) throw new ClientException(CostBudgetErrorCodeEnum.VERSION_CONFLICT);
        }
        if (risk && impact.unavailable()) throw new ClientException(CostBudgetErrorCodeEnum.NOT_READY);
        if (risk && !proofValid || confirming && !proofValid) {
            var proof = confirmation.issue(userId, tenantId, settingsVersion, costVersion, desired, JSON.toJSONString(impact.limits()));
            String warning = WARNING + (impact.limits().isEmpty() ? "" : "\n保存后将立即限制租户全部新的模型调用，预计最晚恢复：" + impact.retryAt() + "（GMT+8）。");
            return new TenantSettingsSaveRespDTO("CONFIRMATION_REQUIRED", true, warning, proof.token(), time.iso(time.local(proof.expiresAt())),
                    settingsVersion, costVersion, impact.limits(), null, confirming ? before : null);
        }
        // 先保存基础设置，旧入口同样维护版本；成本变化也只令整份设置递增一次。
        var savedSettings = settings.updateSettings(userId, tenantId, request);
        if (costChanged) {
            target.setConfigVersion(Math.addExact(current.getConfigVersion(), 1)); target.setUpdateTime(time.local(time.now()));
            persist(target);
            if (settingsVersion.equals(savedSettings.getSettingsVersion())) {
                long next = Math.addExact(tenant.getSettingsVersion(), 1);
                tenants.update(Wrappers.lambdaUpdate(TenantDO.class).eq(TenantDO::getTenantId, tenantId)
                        .eq(TenantDO::getSettingsVersion, tenant.getSettingsVersion()).set(TenantDO::getSettingsVersion, next));
                savedSettings.setSettingsVersion(String.valueOf(next));
            }
            activities.record(tenantId, com.yonagi.verse.async.event.TenantActivityDraft.of(TenantActivityType.TENANT_SETTINGS_UPDATED).actor(userId)
                    .target(TenantActivityTargetType.TENANT,tenantId,request.getName()).detail("changedFields", List.of("costLimit","costWarning")));
            ledger.capture(target);
        }
        return TenantSettingsSaveRespDTO.saved(new TenantSettingsSaveRespDTO.Snapshot(savedSettings,
                TenantCostBudgetLedger.config(target), TenantCostBudgetLedger.warning(target)));
    }
    private TenantSettingsSaveRespDTO.Snapshot snapshot(Long userId, Long tenantId, TenantCostBudgetDO row) {
        return new TenantSettingsSaveRespDTO.Snapshot(settings.getSettings(userId, tenantId), TenantCostBudgetLedger.config(row), TenantCostBudgetLedger.warning(row));
    }
    static boolean needsConfirmation(TenantCostBudgetDO old, TenantCostBudgetDO target) {
        return Boolean.TRUE.equals(target.getCostLimitEnabled()) && (!Boolean.TRUE.equals(old.getCostLimitEnabled())
                || !Objects.equals(old.getDailyLimitFen(), target.getDailyLimitFen()) || !Objects.equals(old.getWeeklyLimitFen(), target.getWeeklyLimitFen())
                || !Objects.equals(old.getMonthlyLimitFen(), target.getMonthlyLimitFen()));
    }
    private static TenantCostBudgetDO copy(TenantCostBudgetDO row) { return JSON.parseObject(JSON.toJSONString(row), TenantCostBudgetDO.class); }
    private void merge(TenantCostBudgetDO row, CostLimitPatch limit, CostWarningPatch warning) {
        if (limit != null) {
            if (limit.getEnabled() != null) row.setCostLimitEnabled(limit.getEnabled());
            if (limit.isDailyPresent()) row.setDailyLimitFen(decimal(limit.getDailyLimitFen()));
            if (limit.isWeeklyPresent()) row.setWeeklyLimitFen(decimal(limit.getWeeklyLimitFen()));
            if (limit.isMonthlyPresent()) row.setMonthlyLimitFen(decimal(limit.getMonthlyLimitFen()));
        }
        if (warning != null) {
            if (warning.getEnabled() != null) row.setWarningEnabled(warning.getEnabled());
            if (warning.getThresholdPercent() != null) row.setWarningThresholdPercent(warning.getThresholdPercent());
            if (warning.getChannels() != null) row.setWarningChannels(JSON.toJSONString(warning.getChannels()));
        }
        boolean enabled = Boolean.TRUE.equals(row.getCostLimitEnabled());
        if (enabled && row.getDailyLimitFen() == null && row.getWeeklyLimitFen() == null && row.getMonthlyLimitFen() == null)
            throw new ClientException(CostBudgetErrorCodeEnum.INVALID_CONFIG);
        if ((enabled && Boolean.TRUE.equals(row.getWarningEnabled()) || warning != null && Boolean.TRUE.equals(warning.getEnabled()))
                && (!enabled || TenantCostBudgetLedger.channels(row).isEmpty() || row.getWarningThresholdPercent() < 50 || row.getWarningThresholdPercent() > 99))
            throw new ClientException(CostBudgetErrorCodeEnum.WARNING_INVALID);
    }
    private void persist(TenantCostBudgetDO row) {
        budgets.update(Wrappers.lambdaUpdate(TenantCostBudgetDO.class).eq(TenantCostBudgetDO::getId, row.getId())
                .set(TenantCostBudgetDO::getCostLimitEnabled, row.getCostLimitEnabled()).set(TenantCostBudgetDO::getDailyLimitFen, row.getDailyLimitFen())
                .set(TenantCostBudgetDO::getWeeklyLimitFen, row.getWeeklyLimitFen()).set(TenantCostBudgetDO::getMonthlyLimitFen, row.getMonthlyLimitFen())
                .set(TenantCostBudgetDO::getWarningEnabled, row.getWarningEnabled()).set(TenantCostBudgetDO::getWarningThresholdPercent, row.getWarningThresholdPercent())
                .set(TenantCostBudgetDO::getWarningChannels, row.getWarningChannels()).set(TenantCostBudgetDO::getConfigVersion, row.getConfigVersion())
                .set(TenantCostBudgetDO::getUpdateTime, row.getUpdateTime()));
    }
    private static boolean explicitTarget(TenantSettingsSaveReqDTO r) {
        var l = r.getCostLimit(); var w = r.getCostWarning();
        return l != null && l.getEnabled() != null && l.isDailyPresent() && l.isWeeklyPresent() && l.isMonthlyPresent()
                && w != null && w.getEnabled() != null && w.getThresholdPercent() != null && w.getChannels() != null && r.getPlaygroundEnabled() != null;
    }
    private static void normalize(TenantSettingsSaveReqDTO r, TenantDO tenant) {
        r.setName(StrUtil.trim(r.getName())); r.setDescription(StrUtil.trimToNull(r.getDescription()));
        if ("PERSONAL".equals(tenant.getType()) && !Integer.valueOf(0).equals(r.getJoinApprovalMode())) throw new ClientException(TenantErrorCodeEnum.TENANT_APPROVAL_MODE_INVALID);
        if (r.getPlaygroundEnabled() == null) r.setPlaygroundEnabled(Integer.valueOf(1).equals(tenant.getPlaygroundEnabled()));
        if (Integer.valueOf(0).equals(r.getRateLimitRpm())) r.setRateLimitRpm(null);
        if (Integer.valueOf(0).equals(r.getRateLimitTpm())) r.setRateLimitTpm(null);
    }
    private static String canonical(TenantSettingsRespDTO r, TenantCostBudgetDO cost) {
        return canonicalValues(r.getName(), r.getDescription(), r.getJoinApprovalMode(), r.getAuditEnabled(), r.getActivityRecordingEnabled(),
                r.getPlaygroundEnabled(), r.getRateLimitRpm(), r.getRateLimitTpm(), cost);
    }
    private static String canonical(TenantSettingsSaveReqDTO r, TenantCostBudgetDO cost) {
        return canonicalValues(r.getName(), r.getDescription(), r.getJoinApprovalMode(), r.getAuditEnabled(), r.getActivityRecordingEnabled(),
                r.getPlaygroundEnabled(), r.getRateLimitRpm(), r.getRateLimitTpm(), cost);
    }
    private static String canonicalValues(String name, String description, Integer join, Boolean audit, Boolean activity, Boolean playground,
            Integer rpm, Integer tpm, TenantCostBudgetDO cost) {
        return JSON.toJSONString(Arrays.asList(name, description, join, audit, activity, playground, rpm, tpm, costCanonical(cost)));
    }
    private static String costCanonical(TenantCostBudgetDO row) {
        var c = TenantCostBudgetLedger.config(row);
        return JSON.toJSONString(Arrays.asList(c.enabled(), c.dailyLimitFen(), c.weeklyLimitFen(), c.monthlyLimitFen(),
                row.getWarningEnabled(), row.getWarningThresholdPercent(), TenantCostBudgetLedger.channels(row)));
    }
    private static BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
    private static ClientException denied() { return new ClientException(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED); }
}
