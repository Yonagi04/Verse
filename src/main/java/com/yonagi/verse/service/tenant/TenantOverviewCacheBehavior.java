package com.yonagi.verse.service.tenant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.yonagi.verse.common.cache.*;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/** 概览结果的时间边界与完整性约束。 */
@Component
public class TenantOverviewCacheBehavior implements QueryCacheBehavior {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
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
