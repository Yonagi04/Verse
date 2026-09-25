package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.LlmAuditLogDO;
import com.yonagi.verse.dao.mapper.LlmAuditLogMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.service.UserTenantService;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlaygroundAuditQueryPrivacyTest {
    @Test
    void administratorCannotReadStoredPlaygroundBodyEvenIfOldRowHasObjectKeys() {
        LlmAuditLogMapper mapper = mock(LlmAuditLogMapper.class);
        UserTenantService membership = mock(UserTenantService.class);
        S3Client s3 = mock(S3Client.class);
        when(membership.isUserJoinedTenant(1L, 2L)).thenReturn(true);
        LlmAuditLogDO row = new LlmAuditLogDO();
        row.setSource("PLAYGROUND");
        row.setTenantId(2L);
        row.setUserId(7L);
        row.setPromptPreview("leaked question");
        row.setResponsePreview("leaked answer");
        row.setPromptObjectKey("old-prompt-object");
        row.setResponseObjectKey("old-response-object");
        when(mapper.selectOne(any())).thenReturn(row);
        LlmAuditServiceImpl service = new LlmAuditServiceImpl(mapper, mock(UserMapper.class), membership, s3);

        var detail = service.getAuditDetail(new UserContext().setUserId(1L)
                .setCurrentTenantId(2L).setRole("ADMIN"), 2L, 99L);

        assertNull(detail.getPromptPreview());
        assertNull(detail.getResponsePreview());
        assertNull(detail.getPrompt());
        assertNull(detail.getResponse());
        verifyNoInteractions(s3);
    }
}
