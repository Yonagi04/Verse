package com.yonagi.verse.async.mq;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.event.CostReconciliationControlEvent;
import com.yonagi.verse.service.budget.CostReconciliationDeliveryService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQPushConsumerLifecycleListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** 专用集群消费资源；同步返回前完成业务提交，异常交给原生消费重试。 */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = "%DLQ%${verse.llm.cost-reconciliation.control-group:verse-cost-reconciliation-control}", consumerGroup = "${verse.llm.cost-reconciliation.control-group:verse-cost-reconciliation-control}-dlq-observer",
        consumeThreadNumber = 1, consumeThreadMax = 1, consumeMessageBatchMaxSize = 1,
        pullBatchSize = 1, maxReconsumeTimes = 16, consumeTimeout = 1)
public class CostReconciliationControlDlqObserver implements RocketMQListener<MessageExt>, RocketMQPushConsumerLifecycleListener {
    private final CostReconciliationDeliveryService delivery;

    @Override
    public void onMessage(MessageExt message) {
        if (message.getBody() == null || message.getBody().length > 16 * 1024) {
            throw new IllegalArgumentException("invalid reconciliation message size");
        }
        var event = JSON.parseObject(new String(message.getBody(), StandardCharsets.UTF_8), CostReconciliationControlEvent.class);
        delivery.controlDeadLetter(event);
    }

    @Override
    public void prepareStart(DefaultMQPushConsumer consumer) {
        consumer.setPullThresholdForQueue(8);
        consumer.setPullThresholdForTopic(32);
        consumer.setPullThresholdSizeForQueue(4);
        consumer.setPullThresholdSizeForTopic(16);
        consumer.setConsumeMessageBatchMaxSize(1);
    }
}
