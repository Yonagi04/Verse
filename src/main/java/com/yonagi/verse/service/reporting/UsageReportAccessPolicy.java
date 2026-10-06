package com.yonagi.verse.service.reporting;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.cache.QueryAccessPolicy;
import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.ApiKeyMapper;
import com.yonagi.verse.dao.mapper.LlmServiceMapper;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.*;
import java.util.Objects;

/** 报表授权与过滤条件的权威实现，不依赖结果缓存或报表 Service。 */
@Component
@RequiredArgsConstructor
public class UsageReportAccessPolicy implements QueryAccessPolicy {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final UserTenantService userTenantService;
    private final UsageReportingProperties properties;
    private final ApiKeyMapper apiKeyMapper;
    private final LlmServiceMapper llmServiceMapper;

    public UsageReportFilter resolveFilter(UserContext context, Long tenantId, UsageGranularity granularity,
        LocalDateTime from, LocalDateTime to, Long requestedUserId, Long apiKeyId, Long serviceId) {
        RoleEnum role = requireRole(context, tenantId);
        boolean canReadAll = role.isAdmin();
        if (requestedUserId!=null && !canReadAll && !Objects.equals(requestedUserId,context.getUserId()))
            throw new ClientException(UsageReportingErrorCodeEnum.PERMISSION_DENIED);
        Long effectiveUserId = canReadAll ? requestedUserId : context.getUserId();
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

    private RoleEnum requireRole(UserContext context, Long tenantId) {
        if (context == null || context.getUserId() == null || tenantId == null
                || !Boolean.TRUE.equals(userTenantService.isUserJoinedTenant(context.getUserId(), tenantId)))
            throw new ClientException(UsageReportingErrorCodeEnum.PERMISSION_DENIED);
        String current = userTenantService.getRoleByUserIdAndTenantId(context.getUserId(), tenantId);
        if (context.getRole() != null && !context.getRole().equals(current))
            throw new ClientException(TenantErrorCodeEnum.TENANT_CONTEXT_MISMATCH);
        try { return RoleEnum.valueOf(current); }
        catch (IllegalArgumentException | NullPointerException error) {
            throw new ClientException(UsageReportingErrorCodeEnum.PERMISSION_DENIED);
        }
    }

    public void checkBreakdown(UsageReportFilter filter, UsageBreakdownDimension dimension, int limit) {
        if (limit < 1 || limit > 100) throw new ClientException(UsageReportingErrorCodeEnum.FILTER_INVALID);
        if (dimension == UsageBreakdownDimension.MEMBER && !filter.canReadAll())
            throw new ClientException(UsageReportingErrorCodeEnum.PERMISSION_DENIED);
    }

    @Override public void check(Object[] args) {
        UsageReportFilter filter = args.length >= 6
                ? resolveFilter((UserContext) args[0], (Long) args[1], (UsageGranularity) args[2],
                        (LocalDateTime) args[3], (LocalDateTime) args[4], (Long) args[5],
                        args.length > 6 ? (Long) args[6] : null, args.length > 7 ? (Long) args[7] : null)
                : resolveFilter((UserContext) args[0], (Long) args[1], UsageGranularity.DAY, null, null, null, null, null);
        if (args.length == 11) checkBreakdown(filter, (UsageBreakdownDimension) args[8], (Integer) args[10]);
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
    private record Range(LocalDateTime from, LocalDateTime to) { }
}
