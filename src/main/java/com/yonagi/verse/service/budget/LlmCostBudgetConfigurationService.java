package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.pricing.PricingConfigurationService;
import com.yonagi.verse.service.tenant.TenantAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** 管理权限、最终价格和预算配置在同一个本地事务校验。所有读取不缓存。 */
@Service @RequiredArgsConstructor
public class LlmCostBudgetConfigurationService {
    private final LlmCostBudgetMapper budgets;
    private final LlmServiceMapper services;
    private final PricingConfigurationService pricing;
    private final TenantAccessPolicy access;
    private final LlmCostBudgetLedger ledger;
    private final BudgetPeriodResolver time;

    public String requireMember(Long tenantId, Long userId) {
        access.activeTenant(tenantId);
        String role = budgets.role(tenantId, userId);
        if (!List.of("MEMBER", "ADMIN", "SUPER_ADMIN").contains(role == null ? "" : role))
            throw new ClientException(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
        return role;
    }
    public void requireAdmin(Long tenantId, Long userId) {
        if (!isAdmin(requireMember(tenantId, userId))) throw new ClientException(TenantErrorCodeEnum.TENANT_PERMISSION_DENIED);
    }
    private boolean isAdmin(String role) { return "ADMIN".equals(role) || "SUPER_ADMIN".equals(role); }

    /** 新模型无历史，只在创建模型的事务内初始化 READY。 */
    public void create(Long userId, LlmServiceDO service, CostLimitPatch limit, CostWarningPatch warning, PricingConfigReqDTO price) {
        requireAdmin(service.getTenantId(), userId);
        LlmCostBudgetDO row = new LlmCostBudgetDO(); row.setId(SnowflakeIdUtil.nextId());
        row.setTenantId(service.getTenantId()); row.setServiceId(service.getServiceId()); row.setCostLimitEnabled(false);
        row.setWarningEnabled(false); row.setWarningThresholdPercent(80); row.setWarningChannels("[]"); row.setConfigVersion(0L);
        row.setCreateTime(time.local(time.now())); row.setUpdateTime(row.getCreateTime());
        merge(row, limit, warning, price, service, true); budgets.insert(row);
    }
    /** 调用方已锁模型元数据；此方法不使用 REQUIRES_NEW，价格保存失败整次回滚。 */
    public void mergeForEdit(Long userId, LlmServiceDO service, CostLimitPatch limit, CostWarningPatch warning, PricingConfigReqDTO price) {
        requireAdmin(service.getTenantId(), userId);
        if (limit == null && warning == null && price == null) return;
        LlmCostBudgetDO row = ledger.lock(service.getTenantId(), service.getServiceId());
        merge(row, limit, warning, price, service, false);
        save(row);
        ledger.capture(row);
    }
    private void merge(LlmCostBudgetDO row, CostLimitPatch patch, CostWarningPatch warning, PricingConfigReqDTO price,
            LlmServiceDO service, boolean creating) {
        if (!creating && (patch != null || warning != null)) {
            if (patch == null || patch.getExpectedVersion() == null) throw new ClientException(CostBudgetErrorCodeEnum.VERSION_CONFLICT);
            if (!String.valueOf(row.getConfigVersion()).equals(patch.getExpectedVersion())) throw new ClientException(CostBudgetErrorCodeEnum.VERSION_CONFLICT);
        }
        String previous = JSON.toJSONString(List.of(LlmCostBudgetLedger.config(row), Boolean.TRUE.equals(row.getWarningEnabled()),
                row.getWarningThresholdPercent(), LlmCostBudgetLedger.channels(row)));
        if (patch != null) {
            if (patch.getEnabled() != null) row.setCostLimitEnabled(patch.getEnabled());
            if (patch.isDailyPresent()) row.setDailyLimitFen(decimal(patch.getDailyLimitFen()));
            if (patch.isWeeklyPresent()) row.setWeeklyLimitFen(decimal(patch.getWeeklyLimitFen()));
            if (patch.isMonthlyPresent()) row.setMonthlyLimitFen(decimal(patch.getMonthlyLimitFen()));
        }
        if (warning != null) {
            if (warning.getEnabled() != null) row.setWarningEnabled(warning.getEnabled());
            if (warning.getThresholdPercent() != null) row.setWarningThresholdPercent(warning.getThresholdPercent());
            if (warning.getChannels() != null) row.setWarningChannels(JSON.toJSONString(warning.getChannels()));
        }
        boolean enabled = Boolean.TRUE.equals(row.getCostLimitEnabled());
        if (enabled && price != null && Boolean.FALSE.equals(price.getEnabled()))
            throw new ClientException(CostBudgetErrorCodeEnum.LLM_PRICE_DISABLE_REQUIRES_BUDGET_OFF);
        boolean eligible = price == null ? pricing.eligible(service.getTenantId(), service.getServiceId()) : Boolean.TRUE.equals(price.getEnabled());
        if (eligible && price != null) pricing.validate(price);
        if (enabled) {
            if (!eligible) throw new ClientException(CostBudgetErrorCodeEnum.LLM_PRICE_REQUIRED);
            if (row.getDailyLimitFen() == null && row.getWeeklyLimitFen() == null && row.getMonthlyLimitFen() == null)
                throw new ClientException(CostBudgetErrorCodeEnum.INVALID_CONFIG);
            // 关闭预警不依赖费用恢复；金额变更/启用仍要求完整数据。
            boolean onlyWarningOff = patch != null && patch.getEnabled() == null && !patch.isDailyPresent()
                    && !patch.isWeeklyPresent() && !patch.isMonthlyPresent() && warning != null
                    && Boolean.FALSE.equals(warning.getEnabled()) && price == null;
            if (!onlyWarningOff && !ledger.ready(row, time.now())) throw new ClientException(CostBudgetErrorCodeEnum.NOT_READY);
        }
        boolean explicitWarningOn = warning != null && Boolean.TRUE.equals(warning.getEnabled());
        if ((explicitWarningOn || enabled && Boolean.TRUE.equals(row.getWarningEnabled()))
                && (!enabled || !eligible || LlmCostBudgetLedger.channels(row).isEmpty()))
            throw new ClientException(CostBudgetErrorCodeEnum.WARNING_INVALID);
        String next = JSON.toJSONString(List.of(LlmCostBudgetLedger.config(row), Boolean.TRUE.equals(row.getWarningEnabled()),
                row.getWarningThresholdPercent(), LlmCostBudgetLedger.channels(row)));
        if (!previous.equals(next) || price != null && !creating) row.setConfigVersion(Math.addExact(row.getConfigVersion(), 1));
        row.setUpdateTime(time.local(time.now()));
    }
    private void save(LlmCostBudgetDO row) {
        // updateById 会省略 null；清空某周期必须明确写 null。
        budgets.update(Wrappers.lambdaUpdate(LlmCostBudgetDO.class).eq(LlmCostBudgetDO::getId, row.getId())
                .set(LlmCostBudgetDO::getCostLimitEnabled, row.getCostLimitEnabled())
                .set(LlmCostBudgetDO::getDailyLimitFen, row.getDailyLimitFen()).set(LlmCostBudgetDO::getWeeklyLimitFen, row.getWeeklyLimitFen())
                .set(LlmCostBudgetDO::getMonthlyLimitFen, row.getMonthlyLimitFen()).set(LlmCostBudgetDO::getWarningEnabled, row.getWarningEnabled())
                .set(LlmCostBudgetDO::getWarningThresholdPercent, row.getWarningThresholdPercent()).set(LlmCostBudgetDO::getWarningChannels, row.getWarningChannels())
                .set(LlmCostBudgetDO::getConfigVersion, row.getConfigVersion()).set(LlmCostBudgetDO::getUpdateTime, row.getUpdateTime()));
    }
    @Transactional(rollbackFor = Exception.class)
    public LlmCostStatusRespDTO update(Long userId, Long tenantId, Long serviceId, LlmCostBudgetUpdateReqDTO patch) {
        requireAdmin(tenantId, userId);
        var service = lockService(tenantId, serviceId);
        if (patch.getCostLimit() == null && patch.getCostWarning() == null) throw new ClientException(CostBudgetErrorCodeEnum.INVALID_CONFIG);
        mergeForEdit(userId, service, patch.getCostLimit(), patch.getCostWarning(), null);
        return view(tenantId, serviceId, true);
    }
    public LlmServiceDO lockService(Long tenantId, Long serviceId) {
        if (services.lockByServiceId(tenantId, serviceId) == null) throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_IS_NOT_EXIST);
        return services.selectOne(Wrappers.lambdaQuery(LlmServiceDO.class).eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getServiceId, serviceId).eq(LlmServiceDO::getDelFlag, 0));
    }
    @Transactional(rollbackFor = Exception.class)
    public LlmCostStatusRespDTO status(Long userId, Long tenantId, Long serviceId) {
        boolean admin = isAdmin(requireMember(tenantId, userId));
        exists(tenantId, List.of(serviceId));
        return view(tenantId, serviceId, admin);
    }
    @Transactional(rollbackFor = Exception.class)
    public LlmCostStatusRespDTO.Batch statuses(Long userId, Long tenantId, List<Long> serviceIds) {
        boolean admin = isAdmin(requireMember(tenantId, userId));
        if (serviceIds == null || serviceIds.isEmpty() || serviceIds.size() > 100 || serviceIds.stream().anyMatch(Objects::isNull))
            throw new ClientException(CostBudgetErrorCodeEnum.INVALID_CONFIG);
        var ids = serviceIds.stream().distinct().sorted().toList(); exists(tenantId, ids);
        // 同一事务固定锁序；一次批量返回当前页，不逐个开启独立事务。
        return new LlmCostStatusRespDTO.Batch(ledger.snapshots(tenantId, ids,
                () -> admin ? pricing.eligibleBatch(tenantId, ids) : Set.<Long>of()).stream()
                .map(full -> visible(full, admin)).toList());
    }
    private void exists(Long tenantId, List<Long> ids) {
        Long found = services.selectCount(Wrappers.lambdaQuery(LlmServiceDO.class).eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0).in(LlmServiceDO::getServiceId, ids));
        if (found == null || found != ids.size()) throw new ClientException(LlmManageErrorCodeEnum.LLM_SERVICE_IS_NOT_EXIST);
    }
    private LlmCostStatusRespDTO view(Long tenantId, Long serviceId, boolean admin) {
        var full = ledger.snapshot(ledger.lock(tenantId, serviceId), admin && pricing.eligible(tenantId, serviceId));
        return visible(full, admin);
    }
    private LlmCostStatusRespDTO visible(LlmCostStatusRespDTO.Full full, boolean admin) {
        if (admin) return full;
        return new LlmCostStatusRespDTO.Summary(full.serviceId(), "SUMMARY", full.budgetState(), full.retryAt(),
                full.timezone(), full.asOf(), full.limits(), "LIMITED".equals(full.budgetState())
                ? "模型调用成本已达到限额，请联系管理员或等待相关周期重置" : "UNKNOWN".equals(full.budgetState()) ? "预算校验暂不可用" : null);
    }
    private BigDecimal decimal(String value) { return value == null ? null : new BigDecimal(value); }
}
