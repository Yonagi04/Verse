package com.yonagi.verse.service.messaging;

import com.yonagi.verse.dao.entity.MessageSendRecordDO;
import java.util.Date;
import java.util.Objects;

/** 外发许可只属于当前本人、当前收件目标和未过期的执行尝试。数据库锁由记录存储负责。 */
public final class MessageSendAttemptPolicy {
    private MessageSendAttemptPolicy() {}

    public static boolean hasRecordIdentity(SendAttemptContext attempt) {
        return attempt!=null && attempt.channel()!=null && attempt.sendRecordId()!=null;
    }

    public static boolean matches(MessageSendRecordDO row,String scene,String requestId,Long userId,SendAttemptContext attempt,Date now) {
        return row!=null && "SUBMITTING".equals(row.getStatus()) && scene.equals(row.getScene()) && requestId.equals(row.getRequestId())
                && userId.equals(row.getUserId()) && row.getTenantId()==null
                && Objects.equals(attempt.executionToken(),row.getExecutionToken()) && attempt.executionToken()!=null
                && Objects.equals(attempt.attemptCount(),row.getAttemptCount()) && Objects.equals(attempt.recipientHash(),row.getRecipientHash())
                && row.getDeadlineAt()!=null && row.getDeadlineAt().after(now);
    }
}
