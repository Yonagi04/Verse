package com.yonagi.verse.service.user.costalert;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.enums.MessageChannel;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import com.yonagi.verse.service.messaging.SendRecordStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import java.util.function.Predicate;

/** 配置与联系方式维护共用永久取消意图，防止关闭后重开或改号后改回使旧任务复活。 */
@Service @RequiredArgsConstructor
public class UserCostAlertCancellationService {
    private final UserCostAlertDeliveryMapper deliveries;
    private final UserCostAlertEventMapper events;
    private final SendRecordStore records;
    private final BudgetPeriodResolver time;
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void configurationChanged(Long userId,UserCostAlertConfigDO config) {
        var channels=JSON.parseArray(config.getChannelsJson(),String.class);
        cancel(userId,task -> {
            var event=events.selectById(task.getEventId());
            return event==null || !Boolean.TRUE.equals("DAY".equals(event.getPeriodType()) ? config.getDailyEnabled() : config.getWeeklyEnabled()) || !channels.contains(task.getChannel());
        });
    }
    @Transactional(propagation=Propagation.MANDATORY,rollbackFor=Exception.class)
    public void contactsChanged(Long userId,boolean phoneChanged,boolean emailChanged) {
        if (!phoneChanged && !emailChanged) return;
        cancel(userId,task -> phoneChanged && "SMS".equals(task.getChannel()) || emailChanged && "EMAIL".equals(task.getChannel()));
    }
    private void cancel(Long userId,Predicate<UserCostAlertDeliveryDO> affected) {
        var currentEvents=events.selectList(Wrappers.lambdaQuery(UserCostAlertEventDO.class).eq(UserCostAlertEventDO::getUserId,userId)
                .gt(UserCostAlertEventDO::getPeriodEnd,time.local(time.now()))).stream().map(UserCostAlertEventDO::getEventId).toList();
        if (currentEvents.isEmpty()) return;
        for (var task:deliveries.selectList(Wrappers.lambdaQuery(UserCostAlertDeliveryDO.class).eq(UserCostAlertDeliveryDO::getRecipientUserId,userId)
                .in(UserCostAlertDeliveryDO::getEventId,currentEvents)
                .in(UserCostAlertDeliveryDO::getStatus,"PENDING","RETRY_WAIT","CLAIMED","QUEUED").orderByAsc(UserCostAlertDeliveryDO::getDeliveryId))) {
            if (!affected.test(task)) continue;
            boolean inFlight=false;
            if (!"IN_APP".equals(task.getChannel())) {
                var current=records.findSubmission(MessageChannel.valueOf(task.getChannel()),task.getRequestId());
                if (current.isPresent() && !List.of("SUBMITTING","QUEUED").contains(current.get().status())) {
                    deliveries.update(Wrappers.lambdaUpdate(UserCostAlertDeliveryDO.class).eq(UserCostAlertDeliveryDO::getDeliveryId,task.getDeliveryId())
                            .set(UserCostAlertDeliveryDO::getStatus,current.get().status()).set(UserCostAlertDeliveryDO::getReasonCode,current.get().errorCode())
                            .set(UserCostAlertDeliveryDO::getUpdateTime,time.local(time.now())));
                    continue;
                }
                inFlight=current.isPresent() && "SUBMITTING".equals(current.get().status()) && task.getPermittedSendToken()!=null
                        && task.getPermittedSendToken().equals(current.get().executionToken());
            }
            deliveries.update(Wrappers.lambdaUpdate(UserCostAlertDeliveryDO.class).eq(UserCostAlertDeliveryDO::getDeliveryId,task.getDeliveryId())
                    .set(UserCostAlertDeliveryDO::getCancelRequestedAt,time.local(time.now())).set(UserCostAlertDeliveryDO::getReasonCode,"CANCEL_REQUESTED")
                    .set(UserCostAlertDeliveryDO::getStatus,inFlight ? "QUEUED" : "CANCELLED").set(UserCostAlertDeliveryDO::getUpdateTime,time.local(time.now())));
        }
    }
}
