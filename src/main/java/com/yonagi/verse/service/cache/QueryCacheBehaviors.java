package com.yonagi.verse.service.cache;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.cache.*;
import com.yonagi.verse.common.enums.UsageGranularity;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dto.resp.ApiKeyPageRespDTO;
import com.yonagi.verse.dto.resp.TenantInviteListRespDTO;
import com.yonagi.verse.service.impl.ApiKeyServiceImpl;
import com.yonagi.verse.service.impl.PlaygroundWorkbenchServiceImpl;
import com.yonagi.verse.service.impl.TenantInviteServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 已有查询的特殊业务语义，由方法注解选择，通用缓存切面不依赖具体服务。 */
public final class QueryCacheBehaviors {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private QueryCacheBehaviors() { }

    @Component
    public static class Hourly implements QueryCacheBehavior {
        @Override public void contributeParameters(List<Object> parameters, Object[] args) {
            parameters.add(LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.HOURS).toString());
        }
    }

    @Component
    public static class ReportWindow implements QueryCacheBehavior {
        @Override public void contributeParameters(List<Object> parameters, Object[] args) {
            // 显式结束时间的报表不需要分桶；默认窗口只在对应业务边界切换。
            if (args[4] == null) {
                LocalDateTime now = LocalDateTime.now(SHANGHAI);
                parameters.add(switch ((UsageGranularity) args[2]) {
                    case HOUR -> now.truncatedTo(ChronoUnit.HOURS).toString();
                    case DAY -> now.toLocalDate().toString();
                    case WEEK -> now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
                    case MONTH -> now.toLocalDate().withDayOfMonth(1).toString();
                });
            }
        }
    }

    @Component
    public static class Overview implements QueryCacheBehavior {
        @Override public void contributeParameters(List<Object> parameters, Object[] args) {
            parameters.add(LocalDateTime.now(SHANGHAI).toLocalDate().toString());
        }
        @Override public boolean cacheable(Object value) {
            JSONObject result = JSON.parseObject(JSON.toJSONString(value));
            if (result == null) return false;
            if (result.containsKey("items")) {
                return result.getJSONArray("items").stream().allMatch(item -> complete((JSONObject) item));
            }
            return complete(result);
        }
        private static boolean complete(JSONObject value) {
            // 聚合发生降级时不把缺失指标冻结到缓存中。
            return value.get("memberCount") != null && value.get("availableServiceCount") != null;
        }
    }

    @Component
    public static class WorkbenchModels implements QueryCacheBehavior {
        @Override public Object load(Object target, Object[] args, QueryCache.Loader original) {
            return ((PlaygroundWorkbenchServiceImpl) target).modelMetadata((UserContext) args[0], (Long) args[1]);
        }
        @Override @SuppressWarnings("unchecked")
        public Object currentView(Object target, Object[] args, Object cached) {
            return ((PlaygroundWorkbenchServiceImpl) target).withCurrentPrices((Long) args[1], (List<JSONObject>) cached);
        }
    }

    @Component
    public static class WorkbenchList implements QueryCacheBehavior {
        @Override public boolean supports(Object[] args) { return "PRESET".equals(args[2]); }
        @Override public List<Object> parameters(Object[] args) {
            List<Object> parameters = QueryCacheBehavior.super.parameters(args);
            if (args[3] instanceof String keyword) parameters.set(3, keyword.isBlank() ? null : keyword.trim());
            return parameters;
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class WorkbenchDetail implements QueryCacheBehavior {
        private final QueryAccessGuard guard;
        @Override public boolean supports(Object[] args) { return guard.isPreset(args); }
        @Override public void check(Object[] args) { guard.checkWorkspace(args); }
    }

    @Component
    public static class Invites implements QueryCacheBehavior {
        @Override public List<Object> parameters(Object[] args) {
            // 所有分页共享候选集合；读取后实时过滤自然到期记录并分页。
            return new ArrayList<>(Arrays.asList(args).subList(0, 2));
        }
        @Override public Object load(Object target, Object[] args, QueryCache.Loader original) {
            return ((TenantInviteServiceImpl) target).inviteCandidates((Long) args[0], (Long) args[1]);
        }
        @Override public Object currentView(Object target, Object[] args, Object cached) {
            return ((TenantInviteServiceImpl) target).pageAvailableInvites((TenantInviteListRespDTO) cached,
                    (Integer) args[2], (Integer) args[3]);
        }
    }

    @Component
    @RequiredArgsConstructor
    public static class ApiKeys implements QueryCacheBehavior {
        private final QueryCache cache;
        @Override public Object load(Object target, Object[] args, QueryCache.Loader original) {
            return ((ApiKeyServiceImpl) target).listApiKeyMetadata(
                    (Long) args[0], (Long) args[1], (Integer) args[2], (Integer) args[3]);
        }
        @Override public Object currentView(Object target, Object[] args, Object cached) {
            // 高频使用时间只按当前页批量回源，不让每次模型调用清空稳定列表。
            return cache.check(() -> ((ApiKeyServiceImpl) target).withCurrentListState(
                    (Long) args[0], (Long) args[1], (ApiKeyPageRespDTO) cached));
        }
    }
}
