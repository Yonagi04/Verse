package com.yonagi.verse.service.budget;

import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

/** 预算周期固定北京时间，结算与检查分别传入请求开始时间和检查时间。 */
@Component
public class BudgetPeriodResolver {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Clock clock;
    public BudgetPeriodResolver() { this(Clock.systemUTC()); }
    public BudgetPeriodResolver(Clock clock) { this.clock = clock; }
    public Instant now() { return clock.instant(); }
    public LocalDateTime local(Instant instant) { return LocalDateTime.ofInstant(instant, ZONE); }
    public String iso(LocalDateTime time) { return time.atZone(ZONE).toOffsetDateTime().toString(); }
    public List<Period> resolve(Instant instant) {
        LocalDate date = instant.atZone(ZONE).toLocalDate();
        LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate month = date.withDayOfMonth(1);
        return List.of(new Period("DAY", date.atStartOfDay(), date.plusDays(1).atStartOfDay()),
                new Period("WEEK", monday.atStartOfDay(), monday.plusWeeks(1).atStartOfDay()),
                new Period("MONTH", month.atStartOfDay(), month.plusMonths(1).atStartOfDay()));
    }
    public LocalDateTime earliest(Instant time) {
        return resolve(time).stream().map(Period::start).min(LocalDateTime::compareTo).orElseThrow();
    }
    public record Period(String type, LocalDateTime start, LocalDateTime end) { }
}
