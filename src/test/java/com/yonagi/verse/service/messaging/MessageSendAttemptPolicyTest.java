package com.yonagi.verse.service.messaging;

import com.yonagi.verse.common.enums.MessageChannel;
import com.yonagi.verse.dao.entity.EmailSendRecordDO;
import com.yonagi.verse.dao.entity.MessageSendRecordDO;
import com.yonagi.verse.dao.entity.SmsSendRecordDO;
import org.junit.jupiter.api.Test;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class MessageSendAttemptPolicyTest {
    final Date now=new Date(123456789L);
    final String scene="PERSONAL_COST_WARNING";

    @Test void onlyCurrentUnexpiredSelfAttemptQualifiesForEitherChannel() {
        for (var channel:MessageChannel.values()) {
            var attempt=new SendAttemptContext(channel,8L,"token",2,"hash"); var row=current(channel);
            assertTrue(MessageSendAttemptPolicy.hasRecordIdentity(attempt));
            assertTrue(MessageSendAttemptPolicy.matches(row,scene,"r",1L,attempt,now));
            row.setDeadlineAt(now); assertFalse(MessageSendAttemptPolicy.matches(row,scene,"r",1L,attempt,now));
        }
    }

    @Test void staleIdentityStateOrRecipientNeverQualifiesForPermission() {
        var attempt=new SendAttemptContext(MessageChannel.EMAIL,8L,"token",2,"hash");
        List<Consumer<MessageSendRecordDO>> changes=List.of(row->row.setStatus("QUEUED"),row->row.setScene("RESET_PASSWORD"),row->row.setRequestId("other"),
                row->row.setUserId(2L),row->row.setTenantId(3L),row->row.setExecutionToken("old"),row->row.setAttemptCount(1),row->row.setRecipientHash("old"),row->row.setDeadlineAt(null));
        for (var change:changes) {
            var row=current(MessageChannel.EMAIL); change.accept(row);
            assertFalse(MessageSendAttemptPolicy.matches(row,scene,"r",1L,attempt,now));
        }
        assertFalse(MessageSendAttemptPolicy.matches(null,scene,"r",1L,attempt,now));
        var row=current(MessageChannel.EMAIL); row.setExecutionToken(null);
        assertFalse(MessageSendAttemptPolicy.matches(row,scene,"r",1L,new SendAttemptContext(MessageChannel.EMAIL,8L,null,2,"hash"),now));
    }

    @Test void missingRecordIdentityCannotSelectAChannelRecord() {
        assertFalse(MessageSendAttemptPolicy.hasRecordIdentity(null));
        assertFalse(MessageSendAttemptPolicy.hasRecordIdentity(new SendAttemptContext(null,8L,"token",2,"hash")));
        assertFalse(MessageSendAttemptPolicy.hasRecordIdentity(new SendAttemptContext(MessageChannel.SMS,null,"token",2,"hash")));
    }

    private MessageSendRecordDO current(MessageChannel channel) {
        MessageSendRecordDO row=channel==MessageChannel.SMS ? new SmsSendRecordDO() : new EmailSendRecordDO();
        row.setId(8L); row.setScene(scene); row.setRequestId("r"); row.setUserId(1L); row.setStatus("SUBMITTING"); row.setExecutionToken("token");
        row.setAttemptCount(2); row.setRecipientHash("hash"); row.setDeadlineAt(new Date(now.getTime()+1)); return row;
    }
}
