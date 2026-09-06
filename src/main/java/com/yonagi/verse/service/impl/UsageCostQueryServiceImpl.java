package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.UsageCostErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dao.entity.UsageCostAggregateDO;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import com.yonagi.verse.dto.resp.UsageCostBreakdownRespDTO;
import com.yonagi.verse.dto.resp.UsageCostSummaryRespDTO;
import com.yonagi.verse.dto.resp.UsageCostTimeseriesRespDTO;
import com.yonagi.verse.dto.resp.UsageCostTotalRespDTO;
import com.yonagi.verse.service.UsageCostQueryService;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用量费用查询服务实现。
 *
 * @author Yonagi
 */
@Service
@RequiredArgsConstructor
public class UsageCostQueryServiceImpl implements UsageCostQueryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> GRANULARITIES = Set.of("hour", "day", "week", "month");
    private static final Set<String> SORT_FIELDS = Set.of(
            "requestCount", "inputTokens", "cachedInputTokens", "outputTokens", "totalTokens",
            "estimatedCostFen", "uncalculableRequestCount"
    );

    private final TokenUsageMapper usageMapper;
    private final LlmServiceMapper serviceMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final UserTenantService userTenantService;

    @Override
    public UsageCostSummaryRespDTO summary(UserContext ctx, Long tenantId, OffsetDateTime from,
                                           OffsetDateTime to, Long serviceId, Long apiKeyId) {
        QueryScope scope = validateAndBuildScope(ctx, tenantId, from, to, serviceId, apiKeyId);
        UsageCostAggregateDO aggregate = usageMapper.aggregateCost(
                tenantId, scope.from(), scope.to(), serviceId, apiKeyId, scope.userId()
        );

        UsageCostSummaryRespDTO result = new UsageCostSummaryRespDTO();
        result.setHasData(aggregate != null && value(aggregate.getRequestCount()) > 0);
        result.setUnpricedModelCount(unpricedModels(tenantId));
        result.setTotal(toTotal(aggregate));
        result.setFilter(filter(null, from, to, serviceId, apiKeyId));
        return result;
    }

    @Override
    public UsageCostTimeseriesRespDTO timeseries(UserContext ctx, Long tenantId, String granularity,
                                                 OffsetDateTime from, OffsetDateTime to,
                                                 Long serviceId, Long apiKeyId) {
        if (!GRANULARITIES.contains(granularity)) {
            throw new ClientException(UsageCostErrorCodeEnum.USAGE_GRANULARITY_INVALID);
        }
        QueryScope scope = validateAndBuildScope(ctx, tenantId, from, to, serviceId, apiKeyId);
        List<UsageCostAggregateDO> aggregates = usageMapper.aggregateCostTimeseries(
                tenantId, scope.from(), scope.to(), serviceId, apiKeyId, scope.userId(), granularity
        );
        List<UsageCostTimeseriesRespDTO.Point> points = aggregates.stream()
                .map(row -> toPoint(row, granularity))
                .toList();

        UsageCostTimeseriesRespDTO result = new UsageCostTimeseriesRespDTO();
        result.setHasData(!aggregates.isEmpty());
        result.setUnpricedModelCount(unpricedModels(tenantId));
        result.setPoints(points);
        result.setFilter(filter(granularity, from, to, serviceId, apiKeyId));
        return result;
    }

    @Override
    public UsageCostBreakdownRespDTO breakdown(UserContext ctx, Long tenantId, String dimension,
                                               OffsetDateTime from, OffsetDateTime to,
                                               Long serviceId, Long apiKeyId, Integer pageNum,
                                               Integer pageSize, String sortBy, String sortOrder) {
        if (!"model".equals(dimension) && !"apiKey".equals(dimension)) {
            throw new ClientException(UsageCostErrorCodeEnum.USAGE_DIMENSION_INVALID);
        }
        int actualPageNum = pageNum == null ? 1 : pageNum;
        int actualPageSize = pageSize == null ? DEFAULT_PAGE_SIZE : pageSize;
        String actualSortBy = sortBy == null ? "estimatedCostFen" : sortBy;
        String actualSortOrder = sortOrder == null ? "desc" : sortOrder;
        validatePagination(actualPageNum, actualPageSize);
        validateSort(actualSortBy, actualSortOrder);

        QueryScope scope = validateAndBuildScope(ctx, tenantId, from, to, serviceId, apiKeyId);
        List<UsageCostAggregateDO> aggregates = usageMapper.aggregateCostByDimension(
                tenantId, scope.from(), scope.to(), serviceId, apiKeyId, scope.userId(), dimension
        );
        List<UsageCostBreakdownRespDTO.Item> items = aggregates.stream()
                .map(this::toItem)
                .collect(Collectors.toCollection(ArrayList::new));
        enrichDimensionInfo(tenantId, dimension, items);
        sortItems(items, actualSortBy, actualSortOrder);

        int total = items.size();
        long offset = (long) (actualPageNum - 1) * actualPageSize;
        int fromIndex = (int) Math.min(offset, total);
        int toIndex = Math.min(fromIndex + actualPageSize, total);

        UsageCostBreakdownRespDTO result = new UsageCostBreakdownRespDTO();
        result.setDimension(dimension);
        result.setItems(new ArrayList<>(items.subList(fromIndex, toIndex)));
        result.setTotal((long) total);
        result.setTotalPages((total + actualPageSize - 1L) / actualPageSize);
        result.setPage(actualPageNum);
        result.setPageSize(actualPageSize);
        return result;
    }

    private QueryScope validateAndBuildScope(UserContext ctx, Long tenantId, OffsetDateTime from,
                                              OffsetDateTime to, Long serviceId, Long apiKeyId) {
        validateRange(from, to);
        if (ctx == null || ctx.getUserId() == null || tenantId == null
                || !Boolean.TRUE.equals(userTenantService.isUserJoinedTenant(ctx.getUserId(), tenantId))) {
            throw new ClientException(UsageCostErrorCodeEnum.USAGE_FILTER_FORBIDDEN);
        }

        String role = userTenantService.getRoleByUserIdAndTenantId(ctx.getUserId(), tenantId);
        if (role == null) {
            throw new ClientException(UsageCostErrorCodeEnum.USAGE_FILTER_FORBIDDEN);
        }
        if (!RoleEnum.isValidRole(role)) {
            throw new ClientException(UsageCostErrorCodeEnum.USAGE_FILTER_FORBIDDEN);
        }
        Long scopedUserId = RoleEnum.valueOf(role).isAdmin() ? null : ctx.getUserId();
        validateFilters(tenantId, serviceId, apiKeyId, scopedUserId);
        return new QueryScope(
                from.atZoneSameInstant(SHANGHAI).toLocalDateTime(),
                to.atZoneSameInstant(SHANGHAI).toLocalDateTime(),
                scopedUserId
        );
    }

    private void validateRange(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !from.isBefore(to)
                || Duration.between(from, to).compareTo(Duration.ofDays(366)) > 0) {
            throw new ClientException(UsageCostErrorCodeEnum.USAGE_TIME_RANGE_INVALID);
        }
    }

    private void validateFilters(Long tenantId, Long serviceId, Long apiKeyId, Long scopedUserId) {
        if (serviceId != null && serviceMapper.selectCount(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getServiceId, serviceId)) == 0) {
            throw new ClientException(UsageCostErrorCodeEnum.USAGE_FILTER_FORBIDDEN);
        }
        if (apiKeyId == null) {
            return;
        }
        ApiKeyDO apiKey = apiKeyMapper.selectOne(Wrappers.lambdaQuery(ApiKeyDO.class)
                .eq(ApiKeyDO::getTenantId, tenantId)
                .eq(ApiKeyDO::getApiKeyId, apiKeyId)
                .last("LIMIT 1"));
        if (apiKey == null || scopedUserId != null && !Objects.equals(apiKey.getUserId(), scopedUserId)) {
            throw new ClientException(UsageCostErrorCodeEnum.USAGE_FILTER_FORBIDDEN);
        }
    }

    private void validatePagination(int pageNum, int pageSize) {
        if (pageNum < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ClientException(UsageCostErrorCodeEnum.USAGE_PAGINATION_INVALID);
        }
    }

    private void validateSort(String sortBy, String sortOrder) {
        if (!SORT_FIELDS.contains(sortBy)
                || !"asc".equalsIgnoreCase(sortOrder) && !"desc".equalsIgnoreCase(sortOrder)) {
            throw new ClientException(UsageCostErrorCodeEnum.USAGE_SORT_INVALID);
        }
    }

    private void enrichDimensionInfo(Long tenantId, String dimension,
                                     List<UsageCostBreakdownRespDTO.Item> items) {
        Set<Long> ids = items.stream()
                .map(UsageCostBreakdownRespDTO.Item::getDimensionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return;
        }
        if ("model".equals(dimension)) {
            Map<Long, LlmServiceDO> services = serviceMapper.selectList(Wrappers.lambdaQuery(LlmServiceDO.class)
                            .eq(LlmServiceDO::getTenantId, tenantId)
                            .in(LlmServiceDO::getServiceId, ids))
                    .stream()
                    .collect(Collectors.toMap(LlmServiceDO::getServiceId, Function.identity()));
            items.forEach(item -> fillService(item, services.get(item.getDimensionId())));
            return;
        }
        Map<Long, ApiKeyDO> apiKeys = apiKeyMapper.selectList(Wrappers.lambdaQuery(ApiKeyDO.class)
                        .eq(ApiKeyDO::getTenantId, tenantId)
                        .in(ApiKeyDO::getApiKeyId, ids))
                .stream()
                .collect(Collectors.toMap(ApiKeyDO::getApiKeyId, Function.identity()));
        items.forEach(item -> fillApiKey(item, apiKeys.get(item.getDimensionId())));
    }

    private void fillService(UsageCostBreakdownRespDTO.Item item, LlmServiceDO service) {
        item.setServiceId(item.getDimensionId());
        if (service != null) {
            item.setServiceName(service.getName());
            item.setProvider(service.getProvider());
        }
    }

    private void fillApiKey(UsageCostBreakdownRespDTO.Item item, ApiKeyDO apiKey) {
        item.setApiKeyId(item.getDimensionId());
        if (apiKey != null) {
            item.setApiKeyName(apiKey.getName());
            item.setKeyPrefix(apiKey.getKeyPrefix());
        }
    }

    private void sortItems(List<UsageCostBreakdownRespDTO.Item> items, String sortBy, String sortOrder) {
        boolean descending = "desc".equalsIgnoreCase(sortOrder);
        Comparator<UsageCostBreakdownRespDTO.Item> comparator;
        if ("estimatedCostFen".equals(sortBy)) {
            Comparator<BigDecimal> valueComparator = descending
                    ? Comparator.nullsLast(Comparator.reverseOrder())
                    : Comparator.nullsLast(Comparator.naturalOrder());
            comparator = Comparator.comparing(UsageCostBreakdownRespDTO.Item::getEstimatedCostFen, valueComparator);
        } else {
            comparator = switch (sortBy) {
                case "requestCount" -> Comparator.comparing(UsageCostBreakdownRespDTO.Item::getRequestCount);
                case "inputTokens" -> Comparator.comparing(UsageCostBreakdownRespDTO.Item::getInputTokens);
                case "cachedInputTokens" -> Comparator.comparing(UsageCostBreakdownRespDTO.Item::getCachedInputTokens);
                case "outputTokens" -> Comparator.comparing(UsageCostBreakdownRespDTO.Item::getOutputTokens);
                case "totalTokens" -> Comparator.comparing(UsageCostBreakdownRespDTO.Item::getTotalTokens);
                default -> Comparator.comparing(UsageCostBreakdownRespDTO.Item::getUncalculableRequestCount);
            };
            if (descending) {
                comparator = comparator.reversed();
            }
        }
        items.sort(comparator.thenComparing(UsageCostBreakdownRespDTO.Item::getDimensionId));
    }

    private Long unpricedModels(Long tenantId) {
        return serviceMapper.selectCount(Wrappers.lambdaQuery(LlmServiceDO.class)
                .eq(LlmServiceDO::getTenantId, tenantId)
                .eq(LlmServiceDO::getDelFlag, 0)
                .isNull(LlmServiceDO::getActivePricingId));
    }

    private UsageCostTotalRespDTO toTotal(UsageCostAggregateDO aggregate) {
        UsageCostTotalRespDTO total = new UsageCostTotalRespDTO();
        if (aggregate == null) {
            return total;
        }
        total.setRequestCount(value(aggregate.getRequestCount()));
        total.setInputTokens(value(aggregate.getInputTokens()));
        total.setCachedInputTokens(value(aggregate.getCachedInputTokens()));
        total.setOutputTokens(value(aggregate.getOutputTokens()));
        total.setTotalTokens(value(aggregate.getTotalTokens()));
        total.setEstimatedCostFen(aggregate.getEstimatedCostFen());
        total.setUncalculableRequestCount(value(aggregate.getUncalculableRequestCount()));
        return total;
    }

    private UsageCostTimeseriesRespDTO.Point toPoint(UsageCostAggregateDO aggregate, String granularity) {
        UsageCostTimeseriesRespDTO.Point point = new UsageCostTimeseriesRespDTO.Point();
        copy(toTotal(aggregate), point);
        LocalDateTime bucketStart = aggregate.getBucketStart();
        point.setBucketStart(bucketStart.atZone(SHANGHAI).toOffsetDateTime().toString());
        point.setBucketEnd(next(bucketStart, granularity).atZone(SHANGHAI).toOffsetDateTime().toString());
        return point;
    }

    private UsageCostBreakdownRespDTO.Item toItem(UsageCostAggregateDO aggregate) {
        UsageCostBreakdownRespDTO.Item item = new UsageCostBreakdownRespDTO.Item();
        item.setDimensionId(aggregate.getDimensionId());
        copy(toTotal(aggregate), item);
        return item;
    }

    private LocalDateTime next(LocalDateTime time, String granularity) {
        return switch (granularity) {
            case "hour" -> time.plusHours(1);
            case "day" -> time.plusDays(1);
            case "week" -> time.plusWeeks(1);
            default -> time.plusMonths(1);
        };
    }

    private void copy(UsageCostTotalRespDTO source, UsageCostTotalRespDTO target) {
        target.setRequestCount(source.getRequestCount());
        target.setInputTokens(source.getInputTokens());
        target.setCachedInputTokens(source.getCachedInputTokens());
        target.setOutputTokens(source.getOutputTokens());
        target.setTotalTokens(source.getTotalTokens());
        target.setEstimatedCostFen(source.getEstimatedCostFen());
        target.setUncalculableRequestCount(source.getUncalculableRequestCount());
    }

    private Map<String, Object> filter(String granularity, OffsetDateTime from, OffsetDateTime to,
                                       Long serviceId, Long apiKeyId) {
        Map<String, Object> filter = new LinkedHashMap<>();
        if (granularity != null) {
            filter.put("granularity", granularity);
        }
        filter.put("from", from.toString());
        filter.put("to", to.toString());
        filter.put("serviceId", serviceId);
        filter.put("apiKeyId", apiKeyId);
        return filter;
    }

    private long value(Long value) {
        return value == null ? 0L : value;
    }

    private record QueryScope(LocalDateTime from, LocalDateTime to, Long userId) {
    }
}
