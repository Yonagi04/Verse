package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.service.usage.user.UserModelUsageAccessPolicy;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.config.UserCostAlertDeliveryProperties;
import com.yonagi.verse.common.enums.MessageSubmissionStatus;
import com.yonagi.verse.dao.entity.*;
import com.yonagi.verse.dao.mapper.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.budget.BudgetPeriodResolver;
import com.yonagi.verse.service.messaging.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

/** 许可仅在真正执行消息尝试前提交；确定未外发的重试必须重新取得许可。 */
@Service @RequiredArgsConstructor
public class UserCostAlertSendEligibility implements MessageSendEligibility {
    public static final String SCENE="PERSONAL_COST_WARNING";
    private final UserCostAlertDeliveryProperties properties;
    private final UserModelUsageAccessPolicy access;
    private final UserCostAlertConfigMapper configs;
    private final UserCostAlertEventMapper events;
    private final UserCostAlertDeliveryMapper deliveries;
    private final UserCostAlertChannelReadinessService readiness;
    private final SendRecordStore records;
    private final BudgetPeriodResolver time;

    @Override public MessageSubmissionRespDTO verify(String scene,String requestId) {
        return SCENE.equals(scene) ? cancelled("ATTEMPT_CONTEXT_REQUIRED") : null;
    }
    @Override @Transactional(rollbackFor=Exception.class,isolation=Isolation.READ_COMMITTED)
    public MessageSubmissionRespDTO verify(String scene,String requestId,SendAttemptContext attempt) {
        if (!SCENE.equals(scene)) return null;
        if (attempt==null || !properties.isDeliveryEnabled()) return cancelled("PERSONAL_DELIVERY_DISABLED");
        var lookup=deliveries.selectOne(Wrappers.lambdaQuery(UserCostAlertDeliveryDO.class).eq(UserCostAlertDeliveryDO::getRequestId,requestId));
        if (lookup==null) return cancelled("DELIVERY_MISSING");
        var user=access.lock(lookup.getRecipientUserId()); var config=configs.selectById(user.getUserId());
        var event=events.selectById(lookup.getEventId()); var task=lock(lookup.getDeliveryId());
        if (task==null || !List.of("PENDING","RETRY_WAIT","CLAIMED","QUEUED").contains(task.getStatus())) return cancelled("DELIVERY_CANCELLED");
        if (task.getCancelRequestedAt()!=null) return cancelled("CANCEL_REQUESTED");
        String invalid=stateReason(event,config,task.getChannel());
        if (invalid!=null) return cancelled(invalid);
        if (!task.getChannel().equals(attempt.channel().name()) || task.getContactHash()==null
                || !task.getContactHash().equals(readiness.hash(user,task.getChannel())) || !task.getContactHash().equals(attempt.recipientHash())) return cancelled("CONTACT_CHANGED");
        if (!readiness.readiness(user).stream().anyMatch(row -> row.channel().equals(task.getChannel()) && row.available())) return cancelled("CHANNEL_UNAVAILABLE");
        if (!records.lockAttempt(scene,requestId,user.getUserId(),attempt)) return cancelled("SEND_ATTEMPT_CHANGED");
        task.setSendPermittedAt(time.local(time.now())); task.setPermittedSendToken(attempt.executionToken()); task.setPermittedSendAttempt(attempt.attemptCount());
        task.setUpdateTime(task.getSendPermittedAt()); deliveries.updateById(task);
        return null;
    }
    public String stateReason(UserCostAlertEventDO event,UserCostAlertConfigDO config,String channel) {
        if (!properties.isDeliveryEnabled()) return "PERSONAL_DELIVERY_DISABLED";
        if (event==null || config==null) return "STATE_MISSING";
        if (!event.getPeriodEnd().isAfter(time.local(time.now()))) return "PERIOD_EXPIRED";
        boolean enabled=Boolean.TRUE.equals("DAY".equals(event.getPeriodType()) ? config.getDailyEnabled() : config.getWeeklyEnabled());
        if (!enabled) return "PERIOD_DISABLED";
        return JSON.parseArray(event.getChannelsJson(),String.class).contains(channel) && JSON.parseArray(config.getChannelsJson(),String.class).contains(channel) ? null : "CHANNEL_REMOVED";
    }
    public UserCostAlertDeliveryDO lock(Long deliveryId) {
        return deliveries.selectOne(Wrappers.lambdaQuery(UserCostAlertDeliveryDO.class).eq(UserCostAlertDeliveryDO::getDeliveryId,deliveryId).last("FOR UPDATE"));
    }
    private MessageSubmissionRespDTO cancelled(String reason) { return new MessageSubmissionRespDTO(MessageSubmissionStatus.CANCELLED,null,null,reason); }
}
