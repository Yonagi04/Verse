package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.service.usage.user.UserModelUsageReadService;
import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.*;
import java.time.*;
import java.util.*;
import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.dao.entity.UserCostAlertConfigDO;
import com.yonagi.verse.dao.mapper.UserCostAlertConfigMapper;
import com.yonagi.verse.dto.resp.UserCostAlertSettingsRespDTO;
import com.yonagi.verse.dto.resp.UserCostAlertSettingsRespDTO.*;

/** 本人费用预警设置视图，不查询或组装活动日历。 */
@Service @RequiredArgsConstructor
public class UserCostAlertQueryService {
    private final UserModelUsageAccessPolicy access;
    private final UserModelUsageReadService usage;
    private final UserCostAlertConfigMapper configs;
    private final UserCostAlertTriggerService capture;
    private final UserCostAlertChannelReadinessService readiness;
    private final BudgetPeriodResolver time;
    public static Settings toSettings(UserCostAlertConfigDO row) {
        return row==null ? new Settings("0",new PeriodSettings(false,null),new PeriodSettings(false,null),List.of("IN_APP"))
                : new Settings(String.valueOf(row.getConfigVersion()),new PeriodSettings(Boolean.TRUE.equals(row.getDailyEnabled()),amount(row.getDailyThresholdFen())),
                    new PeriodSettings(Boolean.TRUE.equals(row.getWeeklyEnabled()),amount(row.getWeeklyThresholdFen())),JSON.parseArray(row.getChannelsJson(),String.class));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public UserCostAlertSettingsRespDTO getSettings(Long userId) {
        var user=access.read(userId);
        var now=time.local(time.now()); var config=toSettings(configs.selectById(userId));
        var week=time.resolve(time.now()).get(1);
        var q=usage.snapshot(userId,week.start().toLocalDate(),now.toLocalDate());
        var rows=q.days();
        List<PeriodUsage> periods=new ArrayList<>();
        for (var p:time.resolve(time.now())) {
            if ("MONTH".equals(p.type())) continue;
            var setting="DAY".equals(p.type()) ? config.daily() : config.weekly();
            BigDecimal known=BigDecimal.ZERO; long unknown=0; boolean complete=true;
            for (var date=p.start().toLocalDate();!date.isAfter(now.toLocalDate());date=date.plusDays(1)) {
                var row=rows.get(date); if (row!=null) { known=known.add(row.knownCostFen()); unknown=Math.addExact(unknown,row.unknownCostCount()); }
                complete &= q.complete(date);
            }
            complete &= unknown==0;
            var event=capture.existing(userId,p);
            String state=!setting.enabled() ? "DISABLED" : event!=null ? "TRIGGERED" : complete ? "NOT_REACHED" : "UNKNOWN";
            periods.add(new PeriodUsage(p.type(),time.iso(p.start()),time.iso(p.end()),setting.enabled(),setting.thresholdFen(),
                    "UNAVAILABLE".equals(q.availability()) ? null : amount(known),String.valueOf(unknown),complete,state,event==null ? null : time.iso(event.getTriggeredAt())));
        }
        return new UserCostAlertSettingsRespDTO(config,"CNY","Asia/Shanghai",now.toLocalDate().toString(),time.iso(now),q.availability(),iso(q.updatedAt()),q.lagSeconds(),periods,readiness.readiness(user));
    }
    private String iso(LocalDateTime value) { return value==null ? null : time.iso(value); }
    public static String amount(BigDecimal value) { return value==null ? null : value.stripTrailingZeros().toPlainString(); }
}
