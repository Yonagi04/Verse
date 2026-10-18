package com.yonagi.verse.service.messaging;

import com.yonagi.verse.common.enums.MessageChannel;

/** 由已认领消息生成；不能由业务请求构造来授权外发。 */
public record SendAttemptContext(
        /** 发送通道。 */ MessageChannel channel,
        /** 消息记录主键。 */ Long sendRecordId,
        /** 当前执行令牌。 */ String executionToken,
        /** 当前尝试序号。 */ Integer attemptCount,
        /** 加密入队时的收件哈希。 */ String recipientHash) { }
