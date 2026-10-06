package com.yonagi.verse.service.cache;

import com.yonagi.verse.common.cache.*;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** 按上海自然小时隔离默认时间窗口。 */
@Component
public class HourlyCacheBehavior implements QueryCacheBehavior {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    @Override public void contributeParameters(List<Object> parameters, Object[] args) {
        parameters.add(LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.HOURS).toString());
    }
}
