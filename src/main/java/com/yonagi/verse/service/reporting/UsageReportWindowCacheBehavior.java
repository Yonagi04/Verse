package com.yonagi.verse.service.reporting;

import com.yonagi.verse.common.cache.*;
import com.yonagi.verse.common.enums.UsageGranularity;
import org.springframework.stereotype.Component;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 报表默认窗口的自然时间边界。 */
@Component
public class UsageReportWindowCacheBehavior implements QueryCacheBehavior {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
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
