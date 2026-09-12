package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.enums.UsageReportingErrorCodeEnum;
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
    private final ApiKeyMapper apiKeyMapper;
    private final LlmServiceMapper llmServiceMapper;
    private final UserMapper userMapper;

    @Override
    public UsageReportRespDTO query(UserContext context, Long tenantId, UsageGranularity granularity,
                                    LocalDateTime from, LocalDateTime to, Long requestedUserId) {
        return query(context, tenantId, granularity, from, to, requestedUserId, null, null);
    }

    @Override
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
    public UsageBreakdownRespDTO breakdown(UserContext context, Long tenantId, UsageGranularity granularity,
        LocalDateTime from, LocalDateTime to, Long userId, Long apiKeyId, Long serviceId,
        UsageBreakdownDimension dimension, UsageBreakdownOrder order, int limit) {
        if (limit < 1 || limit > 100) throw new ClientException(UsageReportingErrorCodeEnum.FILTER_INVALID);
        UsageReportFilter filter=resolveFilter(context,tenantId,granularity,from,to,userId,apiKeyId,serviceId);
        if (dimension==UsageBreakdownDimension.MEMBER && !filter.canReadAll())
            throw new ClientException(UsageReportingErrorCodeEnum.PERMISSION_DENIED);
        List<UsageBreakdownRow> rows=aggregateMapper.summarizeBreakdown(dimension.name(),tenantId,filter.userId(),
                filter.apiKeyId(),filter.serviceId(),filter.from(),filter.to());
        Map<Long,String> labels=loadLabels(dimension,tenantId,rows);
        Metrics total=new Metrics(); rows.forEach(total::add);
        Comparator<UsageBreakdownRow> comparator=Comparator.<UsageBreakdownRow, BigDecimal>comparing(r->metric(r,order),BigDecimal::compareTo).reversed()
                .thenComparing(UsageBreakdownRow::getDimensionId,Comparator.nullsLast(Long::compareTo))
                .thenComparing(r->Objects.toString(r.getModel(),""));
        rows.sort(comparator);
        BigDecimal denominator=metric(total,order);
        List<UsageBreakdownRespDTO.Item> items=rows.stream().limit(limit).map(row->{
            UsageBreakdownRespDTO.Item item=new UsageBreakdownRespDTO.Item();
            item.setId(dimension==UsageBreakdownDimension.MODEL
                    ? row.getDimensionId()+":"+Objects.toString(row.getModel(),"") : String.valueOf(row.getDimensionId()));
            item.setLabel(dimension==UsageBreakdownDimension.MODEL?Objects.toString(row.getModel(),"模型 "+row.getDimensionId()):labels.getOrDefault(row.getDimensionId(),fallbackLabel(dimension,row.getDimensionId())));
            item.setMetrics(Metrics.from(row).toDto());
            item.setRatio(denominator.signum()==0?"0":metric(row,order).divide(denominator,8,java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString());
            return item;
        }).toList();
        UsageBreakdownRespDTO response=new UsageBreakdownRespDTO(); response.setDimension(dimension.name().toLowerCase());
        response.setOrderBy(switch(order){case TOTAL_TOKENS->"totalTokens";case ESTIMATED_COST_FEN->"estimatedCostFen";case REQUEST_COUNT->"requestCount";});
        response.setTotal(total.toDto()); response.setItems(items); return response;
    }

    @Override
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
        boolean canReadAll=isAdmin(context,tenantId);
        if (requestedUserId!=null && !canReadAll && !Objects.equals(requestedUserId,context.getUserId()))
            throw new ClientException(UsageReportingErrorCodeEnum.PERMISSION_DENIED);
        Long effectiveUserId=authorize(context,tenantId,requestedUserId);
        if (requestedUserId!=null && canReadAll && !userTenantService.isUserJoinedTenant(requestedUserId,tenantId))
            throw new ClientException(UsageReportingErrorCodeEnum.FILTER_INVALID);
        if (apiKeyId!=null) {
            ApiKeyDO key=apiKeyMapper.selectOne(Wrappers.lambdaQuery(ApiKeyDO.class).eq(ApiKeyDO::getTenantId,tenantId).eq(ApiKeyDO::getApiKeyId,apiKeyId));
            if (key==null || (!canReadAll && !Objects.equals(key.getUserId(),context.getUserId())) || (effectiveUserId!=null&&!Objects.equals(key.getUserId(),effectiveUserId)))
                throw new ClientException(UsageReportingErrorCodeEnum.FILTER_INVALID);
        }
        if (serviceId!=null && !llmServiceMapper.exists(Wrappers.lambdaQuery(LlmServiceDO.class).eq(LlmServiceDO::getTenantId,tenantId).eq(LlmServiceDO::getServiceId,serviceId).eq(LlmServiceDO::getDelFlag,0)))
            throw new ClientException(UsageReportingErrorCodeEnum.FILTER_INVALID);
        Range range=resolveRange(granularity,from,to);
        return new UsageReportFilter(tenantId,effectiveUserId,apiKeyId,serviceId,range.from,range.to,granularity,canReadAll);
    }

    private boolean isAdmin(UserContext context,Long tenantId) { try { return RoleEnum.valueOf(userTenantService.getRoleByUserIdAndTenantId(context.getUserId(),tenantId)).isAdmin(); } catch(Exception e){ return false; } }
    private Map<Long,String> loadLabels(UsageBreakdownDimension dimension,Long tenantId,List<UsageBreakdownRow> rows) {
        Set<Long> ids=rows.stream().map(UsageBreakdownRow::getDimensionId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        if(ids.isEmpty()||dimension==UsageBreakdownDimension.MODEL)return Map.of();
        if(dimension==UsageBreakdownDimension.API_KEY)return apiKeyMapper.selectList(Wrappers.lambdaQuery(ApiKeyDO.class).eq(ApiKeyDO::getTenantId,tenantId).in(ApiKeyDO::getApiKeyId,ids)).stream().collect(java.util.stream.Collectors.toMap(ApiKeyDO::getApiKeyId,k->k.getName()+" ("+k.getKeyPrefix()+")"));
        return userMapper.selectList(Wrappers.lambdaQuery(UserDO.class).in(UserDO::getUserId,ids)).stream().collect(java.util.stream.Collectors.toMap(UserDO::getUserId,UsageReportServiceImpl::displayUser));
    }
    private static String displayUser(UserDO user){return user.getNickname()==null||user.getNickname().isBlank()?user.getUsername():user.getNickname()+" ("+user.getUsername()+")";}
    private static String fallbackLabel(UsageBreakdownDimension d,Long id){return (d==UsageBreakdownDimension.API_KEY?"API Key ":"成员 ")+id;}
    private static BigDecimal metric(UsageBreakdownRow r,UsageBreakdownOrder o){return switch(o){case TOTAL_TOKENS->BigDecimal.valueOf(Metrics.n(r.getTotalTokens()));case REQUEST_COUNT->BigDecimal.valueOf(Metrics.n(r.getRequestCount()));case ESTIMATED_COST_FEN->r.getEstimatedCostFen()==null?BigDecimal.ZERO:r.getEstimatedCostFen();};}
    private static BigDecimal metric(Metrics r,UsageBreakdownOrder o){return switch(o){case TOTAL_TOKENS->BigDecimal.valueOf(r.total);case REQUEST_COUNT->BigDecimal.valueOf(r.requests);case ESTIMATED_COST_FEN->r.cost;};}

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
        void add(UsageBreakdownRow r) { add(from(r)); }
        void add(Metrics r) { input+=r.input; output+=r.output; total+=r.total; requests+=r.requests; exact+=r.exact; estimated+=r.estimated;
            unknown+=r.unknown; calculated+=r.calculated; unpriced+=r.unpriced; uncalculable+=r.uncalculable; notChargeable+=r.notChargeable; cost=cost.add(r.cost); }
        UsageReportRespDTO.UsageMetrics toDto() { UsageReportRespDTO.UsageMetrics d=new UsageReportRespDTO.UsageMetrics(); fill(d); return d; }
        UsageReportRespDTO.UsagePoint toPoint() { UsageReportRespDTO.UsagePoint d=new UsageReportRespDTO.UsagePoint(); fill(d); return d; }
        void fill(UsageReportRespDTO.UsageMetrics d) { d.setInputTokens(String.valueOf(input)); d.setOutputTokens(String.valueOf(output)); d.setTotalTokens(String.valueOf(total));
            d.setRequestCount(String.valueOf(requests)); d.setEstimatedCostFen(cost.toPlainString()); d.setExactUsageCount(String.valueOf(exact));
            d.setEstimatedUsageCount(String.valueOf(estimated)); d.setUnknownUsageCount(String.valueOf(unknown)); d.setCalculatedCount(String.valueOf(calculated));
            d.setUnpricedCount(String.valueOf(unpriced)); d.setUncalculableCount(String.valueOf(uncalculable)); d.setNotChargeableCount(String.valueOf(notChargeable)); }
        static long n(Long v) { return v==null?0:v; }
        static Metrics from(UsageBreakdownRow r){Metrics m=new Metrics();m.input=n(r.getInputTokens());m.output=n(r.getOutputTokens());m.total=n(r.getTotalTokens());m.requests=n(r.getRequestCount());m.exact=n(r.getExactUsageCount());m.estimated=n(r.getEstimatedUsageCount());m.unknown=n(r.getUnknownUsageCount());m.calculated=n(r.getCalculatedCount());m.unpriced=n(r.getUnpricedCount());m.uncalculable=n(r.getUncalculableCount());m.notChargeable=n(r.getNotChargeableCount());m.cost=r.getEstimatedCostFen()==null?BigDecimal.ZERO:r.getEstimatedCostFen();return m;}
    }
}
