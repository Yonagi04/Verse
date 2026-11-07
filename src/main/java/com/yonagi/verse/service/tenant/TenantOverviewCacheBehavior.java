package com.yonagi.verse.service.tenant;

import com.yonagi.verse.common.cache.QueryCacheBehavior;
import com.yonagi.verse.dto.resp.TenantOverviewRespDTO;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 概览结果的时间边界与完整性约束。 */
@Component
public class TenantOverviewCacheBehavior implements QueryCacheBehavior {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    @Override public void contributeParameters(List<Object> parameters, Object[] args) {
        parameters.add(LocalDateTime.now(SHANGHAI).toLocalDate().toString());
    }
    /** 只有完整指标可进入概览缓存，查询降级结果只用于当前响应。 */
    @Override
    public boolean cacheable(Object value) {
        if (value instanceof TenantOverviewRespDTO.Batch batch) {
            return batch.getItems() != null
                    && batch.getItems().stream().allMatch(TenantOverviewCacheBehavior::complete);
        }
        return value instanceof TenantOverviewRespDTO.Item item && complete(item);
    }

    private static boolean complete(TenantOverviewRespDTO.Item value) {
        // 聚合发生降级时不把缺失指标冻结到缓存中。
        return value != null && value.getMemberCount() != null && value.getAvailableServiceCount() != null;
    }
}
