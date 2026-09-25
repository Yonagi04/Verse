package com.yonagi.verse.async.handler;

import com.yonagi.verse.async.event.LlmAuditEvent;
import com.yonagi.verse.dao.entity.LlmAuditLogDO;
import com.yonagi.verse.dao.mapper.LlmAuditLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaygroundAuditPrivacyTest {
    @Test
    void consumerNeverUploadsOrIndexesPlaygroundBodyEvenIfEventContainsIt() {
        LlmAuditLogMapper mapper = mock(LlmAuditLogMapper.class);
        S3Client s3 = mock(S3Client.class);
        LlmAuditEvent event = new LlmAuditEvent();
        event.setSource("PLAYGROUND");
        event.setRequestId("request-1");
        event.setTenantId(2L);
        event.setUserId(3L);
        event.setServiceId(4L);
        event.setModel("model");
        event.setPrompt("private question");
        event.setResponse("private answer");
        event.setStatus("SUCCESS");

        new LlmAuditEventHandler(mapper, s3).onEvent(event);

        ArgumentCaptor<LlmAuditLogDO> saved = ArgumentCaptor.forClass(LlmAuditLogDO.class);
        verify(mapper).insert(saved.capture());
        assertEquals("PLAYGROUND", saved.getValue().getSource());
        assertNull(saved.getValue().getPromptPreview());
        assertNull(saved.getValue().getResponsePreview());
        assertNull(saved.getValue().getPromptObjectKey());
        assertNull(saved.getValue().getResponseObjectKey());
        verifyNoInteractions(s3);
    }
}
