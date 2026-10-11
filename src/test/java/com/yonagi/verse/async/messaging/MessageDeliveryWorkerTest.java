package com.yonagi.verse.async.messaging;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.enums.*;
import com.yonagi.verse.common.messaging.provider.*;
import com.yonagi.verse.dao.entity.EmailSendRecordDO;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import com.yonagi.verse.service.messaging.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageDeliveryWorkerTest {
    @Test void eligibilityCannotSendAfterTheTaskDeadline() {
        var records=mock(SendRecordStore.class); var email=mock(EmailProvider.class);
        var row=new EmailSendRecordDO(); row.setId(1L); row.setProvider("fixture"); row.setAttemptCount(1);
        var deadline=new Date(System.currentTimeMillis()+60_000); row.setDeadlineAt(deadline);
        var claimed=new SendRecordStore.ClaimedTask(row);
        when(records.claim(MessageChannel.EMAIL,1L)).thenReturn(Optional.of(claimed));
        when(records.payload(claimed)).thenReturn(new MessageDeliveryTaskDTO(null,
                new EmailSendReqDTO("deadline-fixture","LLM_COST_LIMIT",1L,2L,"admin@example.test","限额","正文",null),null));
        when(email.name()).thenReturn("fixture");
        var worker=new MessageDeliveryWorker(records,mock(SmsProvider.class),email,mock(VerificationCodeStore.class),
                new MessagingProperties(),new SimpleMeterRegistry());
        // 在复核边界推进夹具截止时间，避免用 sleep 制造时钟竞态。
        worker.setEligibility(List.of((scene,id) -> { deadline.setTime(0); return null; }));
        worker.execute(MessageChannel.EMAIL,1L);
        verify(email,never()).send(any());
        var result=ArgumentCaptor.forClass(MessageSubmissionRespDTO.class);
        verify(records).complete(eq(claimed),result.capture(),anyLong());
        assertEquals(MessageSubmissionStatus.EXPIRED,result.getValue().status());
        assertEquals("TASK_EXPIRED",result.getValue().errorCode());
    }
}
