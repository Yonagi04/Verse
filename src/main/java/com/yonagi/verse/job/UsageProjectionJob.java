package com.yonagi.verse.job;

import com.yonagi.verse.common.config.UsageReportingProperties;
import com.yonagi.verse.service.UsageProjectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** 周期重建用量小时投影并吸收延迟事件。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "verse.llm.usage-reporting", name = "enabled", havingValue = "true", matchIfMissing = true)
public class UsageProjectionJob {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final UsageProjectionService projectionService;
    private final UsageReportingProperties properties;

    /** 重算当前及最近若干小时。 */
    @Scheduled(fixedDelayString = "${verse.llm.usage-reporting.refresh-delay-ms:300000}")
    public void refreshRecent() {
        LocalDateTime to = LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.HOURS).plusHours(1);
        projectionService.rebuild(to.minusHours(properties.getRefreshLookbackHours()), to);
    }

    /** 每日凌晨补偿最近七天，修正长延迟消息。 */
    @Scheduled(cron = "${verse.llm.usage-reporting.compensation-cron:0 20 1 * * ?}", zone = "Asia/Shanghai")
    public void compensate() {
        LocalDateTime to = LocalDateTime.now(SHANGHAI).truncatedTo(ChronoUnit.HOURS).plusHours(1);
        projectionService.rebuild(to.minusDays(properties.getCompensationDays()), to);
        log.info("[usage-reporting] 补偿投影完成: from={}, to={}", to.minusDays(properties.getCompensationDays()), to);
    }
}
