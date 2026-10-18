package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserCostAlertErrorCode;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.req.UserCostAlertSettingsReplaceReqDTO;
import com.yonagi.verse.dto.resp.UserCostAlertSettingsRespDTO;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.*;

@Service @RequiredArgsConstructor
public class UserCostAlertSettingsService {
    private final UserModelUsageAccessPolicy access;
    private final UserCostAlertConfigMapper configs;
    private final UserCostAlertChannelReadinessService readiness;
    private final UserCostAlertTriggerService capture;
    private final UserCostAlertQueryService query;
    private final UserCostAlertCancellationService cancellation;
    private final BudgetPeriodResolver time;

    @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public UserCostAlertSettingsRespDTO replace(Long userId,UserCostAlertSettingsReplaceReqDTO request) {
        var user=access.lock(userId);
        validate(request);
        var sorted=request.channels().stream().sorted().toList();
        var desired=new UserCostAlertSettingsRespDTO.Settings("0",new UserCostAlertSettingsRespDTO.PeriodSettings(request.daily().enabled(),request.daily().thresholdFen()),
                new UserCostAlertSettingsRespDTO.PeriodSettings(request.weekly().enabled(),request.weekly().thresholdFen()),sorted);
        var row=configs.selectById(userId); var current=UserCostAlertQueryService.toSettings(row);
        boolean same=desired.daily().equals(current.daily()) && desired.weekly().equals(current.weekly()) && sorted.equals(current.channels().stream().sorted().toList());
        if (same) return query.getSettings(userId);
        if (!current.version().equals(request.expectedVersion())) throw new ClientException(UserCostAlertErrorCode.VERSION_CONFLICT);
        if (request.daily().enabled() || request.weekly().enabled()) {
            var available=readiness.readiness(user).stream().filter(UserCostAlertSettingsRespDTO.ChannelReadiness::available).map(UserCostAlertSettingsRespDTO.ChannelReadiness::channel).toList();
            if (!available.containsAll(sorted)) throw new ClientException(UserCostAlertErrorCode.CHANNEL_UNAVAILABLE);
        }
        var now=time.local(time.now()); boolean insert=row==null;
        if (insert) { row=new UserCostAlertConfigDO(); row.setUserId(userId); row.setCreateTime(now); row.setConfigVersion(0L); }
        row.setDailyEnabled(desired.daily().enabled()); row.setDailyThresholdFen(decimal(desired.daily().thresholdFen()));
        row.setWeeklyEnabled(desired.weekly().enabled()); row.setWeeklyThresholdFen(decimal(desired.weekly().thresholdFen()));
        row.setChannelsJson(JSON.toJSONString(sorted)); row.setConfigVersion(Math.addExact(row.getConfigVersion(),1)); row.setUpdateTime(now);
        if (insert) configs.insert(row); else {
            // Lambda set 显式清空阈值，不依赖 MyBatis-Plus 的 null 忽略策略。
            configs.update(Wrappers.lambdaUpdate(UserCostAlertConfigDO.class).eq(UserCostAlertConfigDO::getUserId,userId)
                    .set(UserCostAlertConfigDO::getDailyEnabled,row.getDailyEnabled()).set(UserCostAlertConfigDO::getDailyThresholdFen,row.getDailyThresholdFen())
                    .set(UserCostAlertConfigDO::getWeeklyEnabled,row.getWeeklyEnabled()).set(UserCostAlertConfigDO::getWeeklyThresholdFen,row.getWeeklyThresholdFen())
                    .set(UserCostAlertConfigDO::getChannelsJson,row.getChannelsJson()).set(UserCostAlertConfigDO::getConfigVersion,row.getConfigVersion())
                    .set(UserCostAlertConfigDO::getUpdateTime,now));
        }
        cancellation.configurationChanged(userId,row);
        capture.capture(userId);
        return query.getSettings(userId);
    }
    public static void validate(UserCostAlertSettingsReplaceReqDTO request) {
        if (request==null || request.daily()==null || request.weekly()==null || request.expectedVersion()==null || !request.expectedVersion().matches("0|[1-9][0-9]{0,18}")
                || request.channels()==null || request.channels().size()>3 || new HashSet<>(request.channels()).size()!=request.channels().size()
                || request.channels().stream().anyMatch(Objects::isNull) || !List.of("IN_APP","SMS","EMAIL").containsAll(request.channels())) invalid();
        for (var p:List.of(request.daily(),request.weekly())) {
            if (p.enabled()==null || (Boolean.TRUE.equals(p.enabled()) && p.thresholdFen()==null)
                    || p.thresholdFen()!=null && !p.thresholdFen().matches("[1-9][0-9]{0,19}")) invalid();
        }
        if ((request.daily().enabled() || request.weekly().enabled()) && request.channels().isEmpty()) invalid();
    }
    private static void invalid() { throw new ClientException(UserCostAlertErrorCode.INVALID_SETTINGS); }
    private static BigDecimal decimal(String value) { return value==null ? null : new BigDecimal(value); }
}
