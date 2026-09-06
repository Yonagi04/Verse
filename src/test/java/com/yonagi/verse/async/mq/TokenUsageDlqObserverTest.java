package com.yonagi.verse.async.mq;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.event.TokenUsageEvent;
import com.yonagi.verse.dao.mapper.TokenUsageOutboxMapper;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenUsageDlqObserverTest {
    @Test
    void dlqMessageMovesOriginalOutboxEventToReplayableFailure() {
        TokenUsageOutboxMapper mapper = mock(TokenUsageOutboxMapper.class);
        TokenUsageDlqObserver observer = new TokenUsageDlqObserver(mapper);
        TokenUsageEvent event = new TokenUsageEvent();
        MessageExt message = new MessageExt();
        message.setBody(JSON.toJSONString(event).getBytes(StandardCharsets.UTF_8));
        message.setMsgId("msg-dlq");

        observer.onMessage(message);

        verify(mapper).markConsumerDlq(eq(event.getEventId()), contains("msg-dlq"), any());
    }
}
