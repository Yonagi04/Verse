package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.EventTag;
import com.yonagi.verse.async.api.DomainEventHandler;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.dao.entity.TokenUsageDO;
import com.yonagi.verse.dao.mapper.TokenUsageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * Token 消耗事件消费者 — 落 t_token_usage（createTime 手动填充，不依赖 MetaObjectHandler）。
 *
 * @author Yonagi
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenUsageEventHandler implements DomainEventHandler<TokenUsageEvent> {

    private final TokenUsageMapper tokenUsageMapper;

    @Override
    public String eventType() {
        return EventTag.TOKEN_USAGE;
    }

    @Override
    public Class<TokenUsageEvent> eventClass() {
        return TokenUsageEvent.class;
    }

    @Override
    public void onEvent(TokenUsageEvent event) {
        TokenUsageDO tokenUsage = new TokenUsageDO();
        tokenUsage.setUserId(event.getUserId());
        tokenUsage.setTenantId(event.getTenantId());
        tokenUsage.setApiKeyId(event.getApiKeyId());
        tokenUsage.setServiceId(event.getServiceId());
        tokenUsage.setModel(event.getModel());
        tokenUsage.setPromptTokens(event.getPromptTokens());
        tokenUsage.setCompletionTokens(event.getCompletionTokens());
        tokenUsage.setTotalTokens(event.getTotalTokens());
        tokenUsage.setRequestId(event.getRequestId());
        tokenUsage.setCreateTime(new Date());
        tokenUsageMapper.insert(tokenUsage);
    }
}
