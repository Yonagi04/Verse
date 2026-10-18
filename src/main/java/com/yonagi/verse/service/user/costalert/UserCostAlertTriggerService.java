package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.service.usage.user.UserModelUsageReadService;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.util.*;

/** 调用者先锁用户资源行；周期唯一键保留跨配置、跨投影代际的提醒资格。 */
@Service @RequiredArgsConstructor
public class UserCostAlertTriggerService {
    private final UserCostAlertConfigMapper configs;
    private final UserCostAlertEventMapper events;
    private final UserCostAlertDeliveryMapper deliveries;
    private final UserModelUsageReadService usage;
    private final BudgetPeriodResolver time;

    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void capture(Long userId) {
        var config=configs.selectById(userId);
        if (config==null) return;
        for (var period:time.resolve(time.now())) {
            if ("MONTH".equals(period.type())) continue;
            boolean enabled=Boolean.TRUE.equals("DAY".equals(period.type()) ? config.getDailyEnabled() : config.getWeeklyEnabled());
            BigDecimal threshold="DAY".equals(period.type()) ? config.getDailyThresholdFen() : config.getWeeklyThresholdFen();
            var snapshot=usage.periodCost(userId,period.start().toLocalDate(),period.end().toLocalDate());
            if (snapshot.isEmpty()) continue;
            BigDecimal known=snapshot.get().knownCostFen();
            if (!UserCostAlertThresholdPolicy.reached(enabled,threshold,known) || existing(userId,period)!=null) continue;
            var event=new UserCostAlertEventDO(); event.setEventId(String.valueOf(SnowflakeIdUtil.nextId())); event.setUserId(userId);
            event.setPeriodType(period.type()); event.setPeriodStart(period.start()); event.setPeriodEnd(period.end()); event.setTriggeredAt(time.local(time.now()));
            event.setThresholdFen(threshold); event.setKnownCostFen(known); event.setCostComplete(snapshot.get().complete()); event.setConfigVersion(config.getConfigVersion());
            event.setChannelsJson(config.getChannelsJson()); event.setNotificationId(SnowflakeIdUtil.nextId()); events.insert(event);
            for (var channel:JSON.parseArray(config.getChannelsJson(),String.class)) {
                var task=new UserCostAlertDeliveryDO(); task.setDeliveryId(SnowflakeIdUtil.nextId()); task.setEventId(event.getEventId());
                task.setRecipientUserId(userId); task.setChannel(channel);
                task.setRequestId(cn.hutool.crypto.digest.DigestUtil.sha256Hex(event.getEventId()+":"+channel+":"+userId));
                task.setStatus("PENDING"); task.setAttemptCount(0); task.setNextRetryAt(event.getTriggeredAt());
                task.setCreateTime(event.getTriggeredAt()); task.setUpdateTime(event.getTriggeredAt()); deliveries.insert(task);
            }
        }
    }
    public UserCostAlertEventDO existing(Long userId,BudgetPeriodResolver.Period period) {
        return events.selectOne(Wrappers.lambdaQuery(UserCostAlertEventDO.class).eq(UserCostAlertEventDO::getUserId,userId)
                .eq(UserCostAlertEventDO::getPeriodType,period.type()).eq(UserCostAlertEventDO::getPeriodStart,period.start()));
    }
}
