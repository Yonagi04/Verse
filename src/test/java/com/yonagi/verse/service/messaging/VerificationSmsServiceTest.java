package com.yonagi.verse.service.messaging;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.convention.exception.MessageSendException;
import com.yonagi.verse.common.enums.MessagingErrorCode;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.dto.req.SmsSendReqDTO;
import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerificationSmsServiceTest {
    @Test void acceptedUsesConfiguredTemplateAndSecureSixDigitCode() {
        var sender = mock(SmsSendService.class); var codes = mock(VerificationCodeStore.class); var aes = mock(AesUtil.class);
        when(aes.hashForLookup(anyString())).thenReturn("hash");
        when(sender.send(any(), any())).thenReturn(MessageSubmissionRespDTO.queued());
        var properties = new MessagingProperties(); properties.getSms().setClosureTemplateCode("SMS_custom");
        var service = new VerificationSmsService(sender, codes, properties, aes);
        assertTrue(service.sendAccountClosure(1L, "13800138000"));
        var request = ArgumentCaptor.forClass(SmsSendReqDTO.class); verify(sender).send(request.capture(), any());
        assertEquals("SMS_custom", request.getValue().templateCode()); assertEquals(1L, request.getValue().userId());
        assertTrue(request.getValue().templateParams().get("code").matches("[0-9]{6}"));
        verify(codes, never()).restore(any());
    }
    @Test void restoresOnlyDefiniteNonSubmissionAndPreservesUncertainty() {
        for (boolean uncertain : new boolean[]{false, true}) {
            var sender = mock(SmsSendService.class); var codes = mock(VerificationCodeStore.class); var aes = mock(AesUtil.class);
            when(aes.hashForLookup(anyString())).thenReturn("hash");
            var reservation = new VerificationCodeStore.Reservation("code", "rate", "backup", "owner", "123456", "req");
            when(codes.reserve(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt())).thenReturn(reservation);
            when(sender.send(any(), any())).thenThrow(new MessageSendException(MessagingErrorCode.RECORD_FAILED, uncertain));
            var service = new VerificationSmsService(sender, codes, new MessagingProperties(), aes);
            assertThrows(MessageSendException.class, () -> service.sendPasswordReset("13800138000"));
            verify(codes, times(uncertain ? 0 : 1)).restore(reservation);
        }
    }
    @Test void handlesRejectedUnknownAndReservationFailure() {
        var sender = mock(SmsSendService.class); var codes = mock(VerificationCodeStore.class); var aes = mock(AesUtil.class);
        when(aes.hashForLookup(anyString())).thenReturn("hash");
        var service = new VerificationSmsService(sender, codes, new MessagingProperties(), aes);
        var reservation = new VerificationCodeStore.Reservation("code", "rate", "backup", "owner", "123456", "req");
        when(codes.reserve(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt())).thenReturn(reservation);
        when(sender.send(any(), any())).thenReturn(MessageSubmissionRespDTO.rejected("REJECT"));
        assertThrows(MessageSendException.class, () -> service.sendPasswordReset("13800138000")); verify(codes).restore(reservation);
        clearInvocations(codes, sender);
        when(sender.send(any(), any())).thenReturn(MessageSubmissionRespDTO.unknown("TIMEOUT"));
        assertTrue(assertThrows(MessageSendException.class, () -> service.sendPasswordReset("13800138000")).mayHaveBeenSubmitted());
        verify(codes, never()).restore(any());
        clearInvocations(sender);
        when(codes.reserve(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt()))
                .thenThrow(new MessageSendException(MessagingErrorCode.STATE_FAILED, false));
        assertThrows(MessageSendException.class, () -> service.sendPasswordReset("13800138000")); verifyNoInteractions(sender);
    }
}
