package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.TenantOverviewMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dao.projection.TenantOverviewActivityRow;
import com.yonagi.verse.dao.projection.TenantOverviewCountRow;
import com.yonagi.verse.dao.projection.TenantOverviewUsageRow;
import com.yonagi.verse.dto.resp.TenantOverviewRespDTO;
import com.yonagi.verse.service.TenantOverviewService;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 以目标成员关系角色裁剪用量和待办，不依赖当前租户角色，也不改变当前租户状态。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TenantOverviewServiceImpl implements TenantOverviewService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final Map<String, String> ACTIVITY_TITLES = Map.ofEntries(
            Map.entry("TENANT_SETTINGS_UPDATED", "租户设置已更新"),
            Map.entry("TENANT_PROFILE_UPDATED", "租户资料已更新"),
            Map.entry("MEMBER_JOINED", "有成员加入租户"),
            Map.entry("MEMBER_LEFT", "有成员退出租户"),
            Map.entry("MEMBER_REMOVED", "有成员被移除"),
            Map.entry("MEMBER_ROLE_CHANGED", "成员角色已调整"),
            Map.entry("INVITE_ENABLED", "邀请已启用"),
            Map.entry("INVITE_DISABLED", "邀请已停用"),
            Map.entry("LLM_SERVICE_CREATED", "模型服务已创建"),
            Map.entry("LLM_SERVICE_UPDATED", "模型服务已更新"),
            Map.entry("LLM_SERVICE_ENABLED", "模型服务已启用"),
            Map.entry("LLM_SERVICE_DISABLED", "模型服务已停用"),
            Map.entry("LLM_SERVICE_REMOVED", "模型服务已移除"));

    private final UserTenantService userTenantService;
    private final UserTenantMapper userTenantMapper;
    private final TenantMapper tenantMapper;
    private final TenantOverviewMapper overviewMapper;
    private final UsageReportingProperties usageProperties;

    @Override
    public TenantOverviewRespDTO.Batch batch(Long userId) {
        List<UserTenantDO> memberships = userTenantService.getUserTenantList(userId, Boolean.FALSE, 10L);
        Window window = window(LocalDate.now(SHANGHAI));
        TenantOverviewRespDTO.Batch response = new TenantOverviewRespDTO.Batch();
        response.setFrom(window.fromIso());
        response.setTo(window.toIso());
        response.setDataDelayMinutes(dataDelayMinutes());
        if (memberships.isEmpty()) {
            response.setItems(List.of());
            return response;
        }

        List<Long> ids = memberships.stream().map(UserTenantDO::getTenantId).distinct().toList();
        Map<Long, TenantDO> activeTenants = tenantMapper.selectList(Wrappers.lambdaQuery(TenantDO.class)
                .in(TenantDO::getTenantId, ids).eq(TenantDO::getStatus, 1).eq(TenantDO::getDelFlag, 0))
                .stream().collect(Collectors.toMap(TenantDO::getTenantId, tenant -> tenant));
        List<Target> targets = memberships.stream()
                .filter(membership -> activeTenants.containsKey(membership.getTenantId()))
                .map(membership -> new Target(membership, activeTenants.get(membership.getTenantId())))
                .toList();
        Snapshot snapshot = aggregate(userId, targets, window);
        response.setItems(targets.stream().map(target -> item(target, snapshot)).toList());
        response.setUpdatedAt(snapshot.updatedAt());
        return response;
    }

    @Override
    public TenantOverviewRespDTO.Detail detail(Long userId, Long tenantId) {
        if (tenantId == null) throw new ClientException(TenantErrorCodeEnum.TENANT_ID_IS_NULL);
        TenantDO tenant = tenantMapper.selectOne(Wrappers.lambdaQuery(TenantDO.class)
                .eq(TenantDO::getTenantId, tenantId).eq(TenantDO::getStatus, 1)
                .eq(TenantDO::getDelFlag, 0));
        if (tenant == null) throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_EXIST);
        UserTenantDO membership = userTenantMapper.selectActiveMembership(userId, tenantId);
        if (membership == null) throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);

        Window window = window(LocalDate.now(SHANGHAI));
        Snapshot snapshot = aggregate(userId, List.of(new Target(membership, tenant)), window);
        TenantOverviewRespDTO.Item item = item(new Target(membership, tenant), snapshot);
        TenantOverviewRespDTO.Detail response = new TenantOverviewRespDTO.Detail();
        response.setTenantId(item.getTenantId());
        response.setMemberCount(item.getMemberCount());
        response.setAvailableServiceCount(item.getAvailableServiceCount());
        response.setUsage(item.getUsage());
        response.setPendingJoinRequestCount(item.getPendingJoinRequestCount());
        response.setFrom(window.fromIso());
        response.setTo(window.toIso());
        response.setUpdatedAt(snapshot.updatedAt());
        response.setDataDelayMinutes(dataDelayMinutes());
        response.setRecentActivities(recentActivities(tenant));
        return response;
    }

    private Snapshot aggregate(Long userId, List<Target> targets, Window window) {
        if (targets.isEmpty()) return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
        List<Long> ids = targets.stream().map(target -> target.tenant().getTenantId()).toList();
        List<Long> adminIds = targets.stream().filter(Target::admin)
                .map(target -> target.tenant().getTenantId()).toList();
        List<Long> memberIds = targets.stream().filter(target -> !target.admin())
                .map(target -> target.tenant().getTenantId()).toList();
        List<Long> pendingIds = targets.stream().filter(target -> target.admin() && "TEAM".equals(target.tenant().getType()))
                .map(target -> target.tenant().getTenantId()).toList();

        Map<Long, Long> members = safeCounts(() -> overviewMapper.memberCounts(ids), "members");
        Map<Long, Long> services = safeCounts(() -> overviewMapper.availableServiceCounts(ids), "services");
        Map<Long, Long> pending = pendingIds.isEmpty() ? Map.of()
                : safeCounts(() -> overviewMapper.pendingJoinCounts(pendingIds), "pending");
        Map<Long, TenantOverviewUsageRow> adminUsage = !usageProperties.isEnabled() ? null :
                safeUsage(adminIds, null, window, "tenant-usage");
        Map<Long, TenantOverviewUsageRow> memberUsage = !usageProperties.isEnabled() ? null :
                safeUsage(memberIds, userId, window, "self-usage");
        LocalDateTime latest = new ArrayList<>(List.of(adminUsage == null ? Map.<Long, TenantOverviewUsageRow>of() : adminUsage,
                memberUsage == null ? Map.<Long, TenantOverviewUsageRow>of() : memberUsage)).stream()
                .flatMap(map -> map.values().stream()).map(TenantOverviewUsageRow::getMaxUpdatedAt)
                .filter(value -> value != null).max(Comparator.naturalOrder()).orElse(null);
        return new Snapshot(members, services, adminUsage, memberUsage, pending, latest == null ? null : iso(latest));
    }

    private Map<Long, Long> safeCounts(Supplier<List<TenantOverviewCountRow>> query, String dimension) {
        try {
            return query.get().stream().collect(Collectors.toMap(TenantOverviewCountRow::getTenantId,
                    TenantOverviewCountRow::getTotal));
        } catch (RuntimeException error) {
            com.yonagi.verse.common.cache.QueryCacheHealth.degraded();
            log.error("租户概览聚合失败: dimension={}", dimension, error);
            return null;
        }
    }

    private Map<Long, TenantOverviewUsageRow> safeUsage(List<Long> ids, Long userId, Window window, String dimension) {
        if (ids.isEmpty()) return Map.of();
        try {
            return overviewMapper.usage(ids, userId, window.from(), window.to()).stream()
                    .collect(Collectors.toMap(TenantOverviewUsageRow::getTenantId, row -> row));
        } catch (RuntimeException error) {
            com.yonagi.verse.common.cache.QueryCacheHealth.degraded();
            log.error("租户概览用量聚合失败: dimension={}", dimension, error);
            return null;
        }
    }

    private TenantOverviewRespDTO.Item item(Target target, Snapshot snapshot) {
        Long id = target.tenant().getTenantId();
        TenantOverviewRespDTO.Item item = new TenantOverviewRespDTO.Item();
        item.setTenantId(id);
        item.setMemberCount(snapshot.members() == null ? null : snapshot.members().getOrDefault(id, 0L));
        item.setAvailableServiceCount(snapshot.services() == null ? null : snapshot.services().getOrDefault(id, 0L));
        item.setPendingJoinRequestCount(!target.admin() || !"TEAM".equals(target.tenant().getType())
                || snapshot.pending() == null ? null : snapshot.pending().getOrDefault(id, 0L));
        Map<Long, TenantOverviewUsageRow> usageRows = target.admin() ? snapshot.adminUsage() : snapshot.memberUsage();
        if (usageRows != null) item.setUsage(usage(usageRows.get(id), target.admin()));
        return item;
    }

    private TenantOverviewRespDTO.Usage usage(TenantOverviewUsageRow row, boolean admin) {
        long exact = row == null ? 0 : count(row.getExactUsageCount());
        long estimated = row == null ? 0 : count(row.getEstimatedUsageCount());
        long unknown = row == null ? 0 : count(row.getUnknownUsageCount());
        TenantOverviewRespDTO.Usage usage = new TenantOverviewRespDTO.Usage();
        usage.setScope(admin ? "TENANT" : "SELF");
        usage.setTotalTokens(unknown > 0 && exact + estimated == 0 ? null
                : String.valueOf(row == null ? 0 : count(row.getTotalTokens())));
        usage.setRequestCount(String.valueOf(row == null ? 0 : count(row.getRequestCount())));
        usage.setTokenQuality(estimated > 0 || unknown > 0 ? "PARTIAL" : "COMPLETE");
        return usage;
    }

    private List<TenantOverviewRespDTO.Activity> recentActivities(TenantDO tenant) {
        if (!Integer.valueOf(1).equals(tenant.getActivityRecordingEnabled())) return null;
        try {
            return overviewMapper.recentActivities(tenant.getTenantId(), List.copyOf(ACTIVITY_TITLES.keySet()))
                    .stream().filter(row -> ACTIVITY_TITLES.containsKey(row.getType()))
                    .map(this::activity).toList();
        } catch (RuntimeException error) {
            com.yonagi.verse.common.cache.QueryCacheHealth.degraded();
            log.error("租户概览动态摘要查询失败: tenantId={}", tenant.getTenantId(), error);
            return null;
        }
    }

    private TenantOverviewRespDTO.Activity activity(TenantOverviewActivityRow row) {
        TenantOverviewRespDTO.Activity activity = new TenantOverviewRespDTO.Activity();
        activity.setType(row.getType());
        activity.setTitle(ACTIVITY_TITLES.get(row.getType()));
        activity.setOccurredAt(row.getOccurredAt() == null ? null : iso(row.getOccurredAt()));
        return activity;
    }

    private int dataDelayMinutes() {
        return Math.max(1, (int) Math.ceil(usageProperties.getRefreshDelayMs() / 60000D));
    }

    private static long count(Long value) { return value == null ? 0 : value; }
    private static String iso(LocalDateTime time) { return time.atZone(SHANGHAI).toOffsetDateTime().toString(); }
    static Window window(LocalDate today) {
        LocalDateTime from = today.minusDays(29).atStartOfDay();
        LocalDateTime to = today.plusDays(1).atStartOfDay();
        return new Window(from, to, iso(from), iso(to));
    }

    record Window(LocalDateTime from, LocalDateTime to, String fromIso, String toIso) { }
    private record Target(UserTenantDO membership, TenantDO tenant) {
        boolean admin() {
            return "ADMIN".equals(membership.getRole()) || "SUPER_ADMIN".equals(membership.getRole());
        }
    }
    private record Snapshot(Map<Long, Long> members, Map<Long, Long> services,
            Map<Long, TenantOverviewUsageRow> adminUsage, Map<Long, TenantOverviewUsageRow> memberUsage,
            Map<Long, Long> pending, String updatedAt) { }
}
