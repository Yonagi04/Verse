package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.skillhub.SkillDownloadGrantDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillDownloadGrantMapper;
import com.yonagi.verse.dto.resp.SkillDownloadGrantRespDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillDownloadGrantServiceTest {
    private final SkillDownloadGrantMapper mapper = mock(SkillDownloadGrantMapper.class);
    private final SkillDownloadGrantService service = new SkillDownloadGrantService(mapper);

    @BeforeEach
    void mapping() {
        SkillTestFixture.initializeMapping(SkillDownloadGrantDO.class);
    }

    @Test
    void createsShortLivedUnpredictableCapabilityAndStoresOnlyHash() {
        SkillDownloadResource resource = new SkillDownloadResource(70L, "test.zip", "internal", "a".repeat(64), 3L);
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        SkillDownloadGrantRespDTO result = service.create("subject", resource);
        ArgumentCaptor<SkillDownloadGrantDO> saved = ArgumentCaptor.forClass(SkillDownloadGrantDO.class);
        verify(mapper).insert(saved.capture());
        String token = result.downloadUrl().substring(result.downloadUrl().lastIndexOf('/') + 1);
        assertTrue(token.matches("[0-9a-f]{64}"));
        assertEquals(DigestUtil.sha256Hex(token), saved.getValue().getTokenHash());
        assertNotEquals(token, saved.getValue().getTokenHash());
        assertEquals(70L, saved.getValue().getVersionId());
        assertTrue(saved.getValue().getExpiresAt().isAfter(before.plusSeconds(59)));
        assertTrue(saved.getValue().getExpiresAt().isBefore(before.plusSeconds(62)));
        assertFalse(result.toString().contains(token));
    }

    @Test
    void activeGrantCapRejectsWithoutPersistingAnotherToken() {
        when(mapper.selectCount(any())).thenReturn(30L);
        assertThrows(ClientException.class, () -> service.create("subject",
                new SkillDownloadResource(70L, "test.zip", "internal", "a".repeat(64), 3L)));
        verify(mapper, never()).insert(any(SkillDownloadGrantDO.class));
    }

    @Test
    void unknownExpiredAndMalformedTokensCannotResolveButValidTokenKeepsFixedVersion() {
        assertThrows(ClientException.class, () -> service.resolve("short"));
        assertThrows(ClientException.class, () -> service.resolve("a".repeat(64)));
        SkillDownloadGrantDO valid = new SkillDownloadGrantDO();
        valid.setVersionId(70L);
        when(mapper.selectOne(any())).thenReturn(valid);
        assertEquals(70L, service.resolve("b".repeat(64)));
    }
}
