package com.yonagi.verse.service.user.activity;

import com.yonagi.verse.service.usage.user.UserModelUsageReadService;
import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.time.*;
import java.util.*;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserModelActivityCalendarErrorCode;
import com.yonagi.verse.dto.resp.UserModelActivityCalendarRespDTO;
import com.yonagi.verse.dto.resp.UserModelActivityCalendarRespDTO.*;

/** 本人模型成功调用日历，不依赖费用提醒配置或渠道。 */
@Service @RequiredArgsConstructor
public class UserModelActivityCalendarQueryService {
    private final UserModelUsageAccessPolicy access;
    private final UserModelUsageReadService usage;
    private final BudgetPeriodResolver time;
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public UserModelActivityCalendarRespDTO getCalendar(Long userId,String view,Integer year) {
        access.read(userId);
        LocalDate today=time.local(time.now()).toLocalDate();
        if (!("ROLLING_365".equals(view) && year==null) && !("YEAR".equals(view) && year!=null && year>=today.getYear()-2 && year<=today.getYear()))
            throw new ClientException(UserModelActivityCalendarErrorCode.INVALID_RANGE);
        LocalDate from=year==null ? today.minusDays(364) : LocalDate.of(year,1,1);
        LocalDate to=year==null ? today : LocalDate.of(year,12,31);
        var q=usage.snapshot(userId,from,to); var rows=q.days();
        List<Day> result=new ArrayList<>(); List<Coverage> coverage=new ArrayList<>(); BigInteger total=BigInteger.ZERO; boolean complete=true;
        for (var date=from;!date.isAfter(to);date=date.plusDays(1)) {
            var row=rows.get(date); String count=null;
            String state=date.isAfter(today) ? "FUTURE" : "UNAVAILABLE".equals(q.availability()) ? "MISSING" : q.complete(date) ? "COMPLETE" : "PARTIAL";
            if (!"FUTURE".equals(state)) {
                complete &= "COMPLETE".equals(state);
                if (!"MISSING".equals(state)) { count=String.valueOf(row==null ? 0L : row.successCount()); total=total.add(new BigInteger(count)); }
            }
            result.add(new Day(date.toString(),count,state));
            String reason=switch(state) { case "COMPLETE","FUTURE" -> null; default -> q.invalid() ? "FACTS_REQUIRE_REVIEW" : "MISSING".equals(state) ? "STATISTICS_UNAVAILABLE" : "PROCESSING_PENDING"; };
            if (!coverage.isEmpty() && Objects.equals(coverage.getLast().state(),state) && Objects.equals(coverage.getLast().reasonCode(),reason)) {
                var previous=coverage.removeLast(); coverage.add(new Coverage(previous.from(),date.toString(),state,reason));
            } else coverage.add(new Coverage(date.toString(),date.toString(),state,reason));
        }
        return new UserModelActivityCalendarRespDTO(view,year,"Asia/Shanghai",today.toString(),from.toString(),to.toString(),total.toString(),q.availability(),
                time.iso(time.local(time.now())),iso(q.updatedAt()),q.lagSeconds(),complete,coverage,result);
    }
    private String iso(LocalDateTime value) { return value==null ? null : time.iso(value); }
}
