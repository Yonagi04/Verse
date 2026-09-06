package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.enums.UsageReportingErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.mapper.TokenUsageHourlyAggMapper;
import com.yonagi.verse.dao.projection.UsageAggregateRow;
import com.yonagi.verse.dto.resp.UsageDashboardRespDTO;
import com.yonagi.verse.dto.resp.UsageReportRespDTO;
import com.yonagi.verse.service.UsageReportService;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 用量报表查询实现。 */
@Service
@RequiredArgsConstructor
public class UsageReportServiceImpl implements UsageReportService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final TokenUsageHourlyAggMapper aggregateMapper;
    private final UserTenantService userTenantService;
    private final UsageReportingProperties properties;

    @Override
    public UsageReportRespDTO query(UserContext context, Long tenantId, UsageGranularity granularity,
                                    LocalDateTime from, LocalDateTime to, Long requestedUserId) {
        Long effectiveUserId = authorize(context, tenantId, requestedUserId);
        Range range = resolveRange(granularity, from, to);
        List<UsageAggregateRow> rows = aggregateMapper.summarizeHours(tenantId, effectiveUserId, range.from, range.to);
        Map<LocalDateTime, Metrics> buckets = new HashMap<>();
        for (UsageAggregateRow row : rows) buckets.computeIfAbsent(bucket(row.getBucketStart(), granularity), key -> new Metrics()).add(row);

        List<UsageReportRespDTO.UsagePoint> points = new ArrayList<>();
        Metrics total = new Metrics();
        for (LocalDateTime cursor = bucket(range.from, granularity); cursor.isBefore(range.to); cursor = next(cursor, granularity)) {
            Metrics metrics = buckets.getOrDefault(cursor, new Metrics());
            UsageReportRespDTO.UsagePoint point = metrics.toPoint();
            point.setBucket(cursor.toString());
            points.add(point);
            total.add(metrics);
        }
        UsageReportRespDTO response = new UsageReportRespDTO();
        response.setGranularity(granularity.name().toLowerCase()); response.setFrom(range.from.toString());
        response.setTo(range.to.toString()); response.setUpdatedAt(LocalDateTime.now(SHANGHAI).toString());
        response.setDataDelayMinutes(dataDelayMinutes());
        response.setPoints(points); response.setTotal(total.toDto());
        return response;
    }

    @Override
    public UsageDashboardRespDTO dashboard(UserContext context, Long tenantId) {
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        LocalDateTime hourTo = now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
        UsageReportRespDTO hours = query(context, tenantId, UsageGranularity.HOUR, hourTo.minusHours(24), hourTo, null);
        LocalDateTime dayFrom = now.toLocalDate().minusDays(6).atStartOfDay();
        LocalDateTime dayTo = now.toLocalDate().plusDays(1).atStartOfDay();
        UsageReportRespDTO days = query(context, tenantId, UsageGranularity.DAY, dayFrom, dayTo, null);
        UsageReportRespDTO today = query(context, tenantId, UsageGranularity.HOUR, now.toLocalDate().atStartOfDay(), hourTo, null);
        UsageDashboardRespDTO response = new UsageDashboardRespDTO();
        response.setToday(today.getTotal()); response.setRecent24Hours(hours); response.setRecent7Days(days);
        response.setUpdatedAt(LocalDateTime.now(SHANGHAI).toString());
        response.setDataDelayMinutes(dataDelayMinutes());
        return response;
    }

    /** 将后台投影刷新间隔向上取整为用户可理解的分钟数。 */
    private int dataDelayMinutes() {
        return Math.max(1, (int) Math.ceil(properties.getRefreshDelayMs() / 60000D));
    }

    private Long authorize(UserContext context, Long tenantId, Long requestedUserId) {
        if (context == null || tenantId == null || !userTenantService.isUserJoinedTenant(context.getUserId(), tenantId))
            throw new ClientException(UsageReportingErrorCodeEnum.PERMISSION_DENIED);
        RoleEnum role;
        try { role = RoleEnum.valueOf(userTenantService.getRoleByUserIdAndTenantId(context.getUserId(), tenantId)); }
        catch (RuntimeException e) { throw new ClientException(UsageReportingErrorCodeEnum.PERMISSION_DENIED); }
        return role.isAdmin() ? requestedUserId : context.getUserId();
    }

    private Range resolveRange(UsageGranularity granularity, LocalDateTime from, LocalDateTime to) {
        LocalDateTime now = LocalDateTime.now(SHANGHAI);
        if (to == null) to = switch (granularity) {
            case HOUR -> now.truncatedTo(ChronoUnit.HOURS).plusHours(1);
            case DAY -> now.toLocalDate().plusDays(1).atStartOfDay();
            case WEEK -> now.toLocalDate().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay();
            case MONTH -> now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay();
        };
        if (from == null) from = switch (granularity) {
            case HOUR -> to.minusHours(24); case DAY -> to.minusDays(7);
            case WEEK -> to.minusWeeks(8); case MONTH -> to.minusMonths(6);
        };
        if (!from.isBefore(to) || Duration.between(from, to).toDays() > properties.getMaxRangeDays())
            throw new ClientException(UsageReportingErrorCodeEnum.RANGE_INVALID);
        return new Range(from, to);
    }

    private LocalDateTime bucket(LocalDateTime value, UsageGranularity granularity) {
        return switch (granularity) {
            case HOUR -> value.truncatedTo(ChronoUnit.HOURS);
            case DAY -> value.toLocalDate().atStartOfDay();
            case WEEK -> value.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay();
            case MONTH -> value.toLocalDate().withDayOfMonth(1).atStartOfDay();
        };
    }
    private LocalDateTime next(LocalDateTime value, UsageGranularity granularity) {
        return switch (granularity) { case HOUR -> value.plusHours(1); case DAY -> value.plusDays(1); case WEEK -> value.plusWeeks(1); case MONTH -> value.plusMonths(1); };
    }
    private record Range(LocalDateTime from, LocalDateTime to) {}

    private static final class Metrics {
        long input, output, total, requests, exact, estimated, unknown, calculated, unpriced, uncalculable, notChargeable;
        BigDecimal cost = BigDecimal.ZERO;
        void add(UsageAggregateRow r) { input+=n(r.getInputTokens()); output+=n(r.getOutputTokens()); total+=n(r.getTotalTokens()); requests+=n(r.getRequestCount());
            exact+=n(r.getExactUsageCount()); estimated+=n(r.getEstimatedUsageCount()); unknown+=n(r.getUnknownUsageCount());
            calculated+=n(r.getCalculatedCount()); unpriced+=n(r.getUnpricedCount()); uncalculable+=n(r.getUncalculableCount()); notChargeable+=n(r.getNotChargeableCount());
            cost=cost.add(r.getEstimatedCostFen()==null?BigDecimal.ZERO:r.getEstimatedCostFen()); }
        void add(Metrics r) { input+=r.input; output+=r.output; total+=r.total; requests+=r.requests; exact+=r.exact; estimated+=r.estimated;
            unknown+=r.unknown; calculated+=r.calculated; unpriced+=r.unpriced; uncalculable+=r.uncalculable; notChargeable+=r.notChargeable; cost=cost.add(r.cost); }
        UsageReportRespDTO.UsageMetrics toDto() { UsageReportRespDTO.UsageMetrics d=new UsageReportRespDTO.UsageMetrics(); fill(d); return d; }
        UsageReportRespDTO.UsagePoint toPoint() { UsageReportRespDTO.UsagePoint d=new UsageReportRespDTO.UsagePoint(); fill(d); return d; }
        void fill(UsageReportRespDTO.UsageMetrics d) { d.setInputTokens(String.valueOf(input)); d.setOutputTokens(String.valueOf(output)); d.setTotalTokens(String.valueOf(total));
            d.setRequestCount(String.valueOf(requests)); d.setEstimatedCostFen(cost.toPlainString()); d.setExactUsageCount(String.valueOf(exact));
            d.setEstimatedUsageCount(String.valueOf(estimated)); d.setUnknownUsageCount(String.valueOf(unknown)); d.setCalculatedCount(String.valueOf(calculated));
            d.setUnpricedCount(String.valueOf(unpriced)); d.setUncalculableCount(String.valueOf(uncalculable)); d.setNotChargeableCount(String.valueOf(notChargeable)); }
        static long n(Long v) { return v==null?0:v; }
    }
}
