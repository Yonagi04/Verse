package com.yonagi.verse.service.impl;

import com.yonagi.verse.service.cache.HourlyCacheBehavior;
import com.yonagi.verse.service.reporting.UsageReportAccessPolicy;
import com.yonagi.verse.service.reporting.UsageReportWindowCacheBehavior;

import com.yonagi.verse.common.cache.QueryCached;
import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.enums.UsageBreakdownDimension;
import com.yonagi.verse.common.enums.UsageBreakdownOrder;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.mapper.TokenUsageHourlyAggMapper;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.projection.UsageBreakdownRow;
import com.yonagi.verse.dao.projection.UsageAggregateRow;
import com.yonagi.verse.dto.resp.UsageDashboardRespDTO;
import com.yonagi.verse.dto.resp.UsageReportRespDTO;
import com.yonagi.verse.dto.resp.UsageBreakdownRespDTO;
import com.yonagi.verse.dto.resp.UsageFilterOptionsRespDTO;
import com.yonagi.verse.service.UsageReportService;
import com.yonagi.verse.service.reporting.UsageReportFilter;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;

import static com.yonagi.verse.common.cache.QueryCacheTtl.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;

/** 用量报表查询实现。 */
@Service
@RequiredArgsConstructor
public class UsageReportServiceImpl implements UsageReportService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final TokenUsageHourlyAggMapper aggregateMapper;
    private final UserTenantService userTenantService;
    private final UsageReportingProperties properties;
    private final ApiKeyMapper apiKeyMapper;
    private final LlmServiceMapper llmServiceMapper;
    private final UserMapper userMapper;
    private final UsageReportAccessPolicy reportAccess;

    @Override
    @QueryCached(keyPrefix = USAGE_REPORT_QUERY_KEY, seconds = MINUTES_10, access = UsageReportAccessPolicy.class,
            tables = {"t_tenant", "t_user_tenant", "t_user", "t_api_key", "t_llm_service", "t_token_usage_hourly_agg"}, behavior = UsageReportWindowCacheBehavior.class)
    public UsageReportRespDTO query(UserContext context, Long tenantId, UsageGranularity granularity,
                                    LocalDateTime from, LocalDateTime to, Long requestedUserId) {
        return query(context, tenantId, granularity, from, to, requestedUserId, null, null);
    }

    @Override
    @QueryCached(keyPrefix = USAGE_REPORT_QUERY_KEY, seconds = MINUTES_10, access = UsageReportAccessPolicy.class,
            tables = {"t_tenant", "t_user_tenant", "t_user", "t_api_key", "t_llm_service", "t_token_usage_hourly_agg"}, behavior = UsageReportWindowCacheBehavior.class)
    public UsageReportRespDTO query(UserContext context, Long tenantId, UsageGranularity granularity,
                                    LocalDateTime from, LocalDateTime to, Long requestedUserId,
                                    Long apiKeyId, Long serviceId) {
        UsageReportFilter filter=resolveFilter(context,tenantId,granularity,from,to,requestedUserId,apiKeyId,serviceId);
        List<UsageAggregateRow> rows = aggregateMapper.summarizeHours(tenantId, filter.userId(), filter.apiKeyId(),
                filter.serviceId(), filter.from(), filter.to());
        Map<LocalDateTime, Metrics> buckets = new HashMap<>();
        for (UsageAggregateRow row : rows) buckets.computeIfAbsent(bucket(row.getBucketStart(), granularity), key -> new Metrics()).add(row);

        List<UsageReportRespDTO.UsagePoint> points = new ArrayList<>();
        Metrics total = new Metrics();
        for (LocalDateTime cursor = bucket(filter.from(), granularity); cursor.isBefore(filter.to()); cursor = next(cursor, granularity)) {
            Metrics metrics = buckets.getOrDefault(cursor, new Metrics());
            UsageReportRespDTO.UsagePoint point = metrics.toPoint();
            point.setBucket(cursor.toString());
            points.add(point);
            total.add(metrics);
        }
        UsageReportRespDTO response = new UsageReportRespDTO();
        response.setGranularity(granularity.name().toLowerCase()); response.setFrom(filter.from().toString());
        response.setTo(filter.to().toString()); response.setUpdatedAt(LocalDateTime.now(SHANGHAI).toString());
        response.setDataDelayMinutes(dataDelayMinutes());
        response.setPoints(points); response.setTotal(total.toDto());
        return response;
    }

    @Override
    @QueryCached(keyPrefix = USAGE_REPORT_BREAKDOWN_KEY, seconds = MINUTES_10, access = UsageReportAccessPolicy.class,
            tables = {"t_tenant", "t_user_tenant", "t_user", "t_api_key", "t_llm_service", "t_token_usage_hourly_agg"}, behavior = UsageReportWindowCacheBehavior.class)
    public UsageBreakdownRespDTO breakdown(UserContext context, Long tenantId, UsageGranularity granularity,
        LocalDateTime from, LocalDateTime to, Long userId, Long apiKeyId, Long serviceId,
        UsageBreakdownDimension dimension, UsageBreakdownOrder order, int limit) {
        UsageReportFilter filter = resolveFilter(context, tenantId, granularity, from, to, userId, apiKeyId, serviceId);
        reportAccess.checkBreakdown(filter, dimension, limit);
        // SQL 将全量总计放在首行，查询时需为它预留一个位置。
        int rowLimit = Math.incrementExact(limit);
        List<UsageBreakdownRow> rows = aggregateMapper.summarizeRankedBreakdown(dimension, order, filter.tenantId(),
                filter.userId(), filter.apiKeyId(), filter.serviceId(), filter.from(), filter.to(), rowLimit);
        UsageBreakdownRow total = new UsageBreakdownRow();
        if (!rows.isEmpty()) {
            total = rows.getFirst();
            rows = rows.subList(1, rows.size());
        }
        Map<Long, String> labels = loadLabels(dimension, filter.tenantId(), rows);
        BigDecimal denominator = breakdownMetric(total, order);
        List<UsageBreakdownRespDTO.Item> items = rows.stream().map(row -> {
            UsageBreakdownRespDTO.Item item = new UsageBreakdownRespDTO.Item();
            Long id = row.getDimensionId();
            if (dimension == UsageBreakdownDimension.MODEL) {
                item.setId(id + ":" + Objects.toString(row.getModel(), ""));
                item.setLabel(Objects.toString(row.getModel(), "模型 " + id));
            } else {
                item.setId(String.valueOf(id));
                item.setLabel(labels.getOrDefault(id, fallbackLabel(dimension, id)));
            }
            item.setMetrics(breakdownMetrics(row));
            item.setRatio(denominator.signum() == 0 ? "0" : breakdownMetric(row, order)
                    .divide(denominator, 8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
            return item;
        }).toList();
        UsageBreakdownRespDTO response = new UsageBreakdownRespDTO();
        response.setDimension(dimension.name().toLowerCase(Locale.ROOT));
        response.setOrderBy(switch (order) {
            case TOTAL_TOKENS -> "totalTokens";
            case ESTIMATED_COST_FEN -> "estimatedCostFen";
            case REQUEST_COUNT -> "requestCount";
        });
        response.setTotal(breakdownMetrics(total));
        response.setItems(items);
        return response;
    }

    @Override
    @QueryCached(keyPrefix = USAGE_REPORT_FILTER_OPTIONS_KEY, seconds = HOURS_1, access = UsageReportAccessPolicy.class,
            tables = {"t_tenant", "t_user_tenant", "t_user", "t_api_key", "t_llm_service", "t_token_usage_hourly_agg"})
    public UsageFilterOptionsRespDTO filterOptions(UserContext context, Long tenantId) {
        UsageReportFilter filter=resolveFilter(context,tenantId,UsageGranularity.DAY,null,null,null,null,null);
        List<LlmServiceDO> services=llmServiceMapper.selectList(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getTenantId,tenantId).eq(LlmServiceDO::getDelFlag,0).orderByAsc(LlmServiceDO::getName));
        var keyQuery=Wrappers.lambdaQuery(ApiKeyDO.class).eq(ApiKeyDO::getTenantId,tenantId).orderByAsc(ApiKeyDO::getName);
        if (!filter.canReadAll()) keyQuery.eq(ApiKeyDO::getUserId,context.getUserId());
        List<ApiKeyDO> keys=apiKeyMapper.selectList(keyQuery);
        List<UsageFilterOptionsRespDTO.Option> members=List.of();
        if (filter.canReadAll()) {
            List<Long> ids=userTenantService.getTenantAllMembers(tenantId).stream().map(UserTenantDO::getUserId).toList();
            if (!ids.isEmpty()) members=userMapper.selectList(Wrappers.lambdaQuery(UserDO.class).in(UserDO::getUserId,ids)).stream()
                    .map(u->new UsageFilterOptionsRespDTO.Option(String.valueOf(u.getUserId()),displayUser(u))).toList();
        }
        UsageFilterOptionsRespDTO response=new UsageFilterOptionsRespDTO(); response.setCanReadAll(filter.canReadAll());
        response.setServices(services.stream().map(s->new UsageFilterOptionsRespDTO.Option(String.valueOf(s.getServiceId()),s.getName())).toList());
        response.setApiKeys(keys.stream().map(k->new UsageFilterOptionsRespDTO.Option(String.valueOf(k.getApiKeyId()),k.getName()+" ("+k.getKeyPrefix()+")")).toList());
        response.setMembers(members); return response;
    }

    @Override
    public UsageReportFilter resolveFilter(UserContext context, Long tenantId, UsageGranularity granularity,
        LocalDateTime from, LocalDateTime to, Long requestedUserId, Long apiKeyId, Long serviceId) {
        return reportAccess.resolveFilter(context, tenantId, granularity, from, to, requestedUserId, apiKeyId, serviceId);
    }


    private Map<Long, String> loadLabels(UsageBreakdownDimension dimension, Long tenantId,
                                         List<UsageBreakdownRow> rows) {
        if (dimension == UsageBreakdownDimension.MODEL || rows.isEmpty()) {
            return Map.of();
        }
        Set<Long> ids = rows.stream().map(UsageBreakdownRow::getDimensionId).collect(Collectors.toSet());
        if (dimension == UsageBreakdownDimension.API_KEY) {
            return apiKeyMapper.selectList(Wrappers.lambdaQuery(ApiKeyDO.class)
                            .select(ApiKeyDO::getApiKeyId, ApiKeyDO::getName, ApiKeyDO::getKeyPrefix)
                            .eq(ApiKeyDO::getTenantId, tenantId).in(ApiKeyDO::getApiKeyId, ids))
                    .stream().collect(Collectors.toMap(ApiKeyDO::getApiKeyId,
                            key -> key.getName() + " (" + key.getKeyPrefix() + ")", (first, duplicate) -> first));
        }
        return userMapper.selectList(Wrappers.lambdaQuery(UserDO.class)
                        .select(UserDO::getUserId, UserDO::getUsername, UserDO::getNickname)
                        .in(UserDO::getUserId, ids))
                .stream().collect(Collectors.toMap(UserDO::getUserId,
                        user -> Objects.toString(displayUser(user), fallbackLabel(dimension, user.getUserId())),
                        (first, duplicate) -> first));
    }
    private static String displayUser(UserDO user){return user.getNickname()==null||user.getNickname().isBlank()?user.getUsername():user.getNickname()+" ("+user.getUsername()+")";}
    private static String fallbackLabel(UsageBreakdownDimension dimension, Long id) {
        return (dimension == UsageBreakdownDimension.API_KEY ? "API Key " : "成员 ") + id;
    }

    private static BigDecimal breakdownMetric(UsageBreakdownRow row, UsageBreakdownOrder order) {
        return switch (order) {
            case TOTAL_TOKENS -> new BigDecimal(Objects.requireNonNullElse(row.getTotalTokens(), BigInteger.ZERO));
            case REQUEST_COUNT -> new BigDecimal(Objects.requireNonNullElse(row.getRequestCount(), BigInteger.ZERO));
            case ESTIMATED_COST_FEN -> Objects.requireNonNullElse(row.getEstimatedCostFen(), BigDecimal.ZERO);
        };
    }

    private static UsageReportRespDTO.UsageMetrics breakdownMetrics(UsageBreakdownRow row) {
        UsageReportRespDTO.UsageMetrics metrics = new UsageReportRespDTO.UsageMetrics();
        metrics.setInputTokens(Objects.toString(row.getInputTokens(), "0"));
        metrics.setOutputTokens(Objects.toString(row.getOutputTokens(), "0"));
        metrics.setTotalTokens(Objects.toString(row.getTotalTokens(), "0"));
        metrics.setRequestCount(Objects.toString(row.getRequestCount(), "0"));
        metrics.setEstimatedCostFen(Objects.requireNonNullElse(row.getEstimatedCostFen(), BigDecimal.ZERO)
                .toPlainString());
        metrics.setExactUsageCount(Objects.toString(row.getExactUsageCount(), "0"));
        metrics.setEstimatedUsageCount(Objects.toString(row.getEstimatedUsageCount(), "0"));
        metrics.setUnknownUsageCount(Objects.toString(row.getUnknownUsageCount(), "0"));
        metrics.setCalculatedCount(Objects.toString(row.getCalculatedCount(), "0"));
        metrics.setUnpricedCount(Objects.toString(row.getUnpricedCount(), "0"));
        metrics.setUncalculableCount(Objects.toString(row.getUncalculableCount(), "0"));
        metrics.setNotChargeableCount(Objects.toString(row.getNotChargeableCount(), "0"));
        return metrics;
    }

    @Override
    @QueryCached(keyPrefix = USAGE_REPORT_DASHBOARD_KEY, seconds = MINUTES_10, access = UsageReportAccessPolicy.class,
            tables = {"t_tenant", "t_user_tenant", "t_user", "t_api_key", "t_llm_service", "t_token_usage_hourly_agg"}, behavior = HourlyCacheBehavior.class)
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
