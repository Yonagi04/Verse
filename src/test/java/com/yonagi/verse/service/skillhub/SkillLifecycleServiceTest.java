package com.yonagi.verse.service.skillhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.dao.entity.skillhub.SkillAuditRecordDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillAuditRecordMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillOwnerQueryMapper;
import com.yonagi.verse.dao.projection.SkillOwnerProjection;
import com.yonagi.verse.dto.req.SkillUnpublishReqDTO;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillLifecycleServiceTest {
    private static final String KEY = "d47f2997-053a-4cb6-96d3-47a6ce659264";
    private final SkillAccessPolicy access = mock(SkillAccessPolicy.class);
    private final AccountAvailabilityService accounts = mock(AccountAvailabilityService.class);
    private final SkillOwnerQueryMapper query = mock(SkillOwnerQueryMapper.class);
    private final SkillMapper skills = mock(SkillMapper.class);
    private final SkillAuditRecordMapper audits = mock(SkillAuditRecordMapper.class);
    private final SkillIdempotencyService commands = mock(SkillIdempotencyService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
    private final SkillLifecycleService service = new SkillLifecycleService(access, accounts, query, skills, audits,
            commands, mapper, validation.getValidator());
    private final SkillDO skill = SkillTestFixture.skill();
    private final SkillUnpublishReqDTO input = new SkillUnpublishReqDTO(0L, "作者主动下架");

    @BeforeEach
    void owner() {
        SkillTestFixture.initializeMapping(SkillDO.class);
        skill.setStatus("ACTIVE");
        when(access.lockSkill(1L, 20L)).thenReturn(new SkillOwnedResource(SkillTestFixture.publisher(), skill));
        when(accounts.isAccountActive(1L)).thenReturn(true);
    }

    @AfterEach
    void closeValidation() {
        validation.close();
    }

    @Test
    void personalListUsesStringIdsAndRetainsUnpublishedStateWithoutTenant() {
        SkillOwnerProjection row = new SkillOwnerProjection();
        row.setSkillId(20L);
        row.setStatus("OWNER_UNPUBLISHED");
        row.setRevision(1L);
        row.setUpdateTime(SkillTestFixture.now());
        when(query.countOwned(1L)).thenReturn(2L);
        when(query.selectOwnedPage(1L, 12, 0)).thenReturn(List.of(row));
        assertEquals("OWNER_UNPUBLISHED", service.mine(1L, 1).items().getFirst().status());
        assertEquals("20", service.mine(1L, 1).items().getFirst().skillId());
        row.setDraftId(30L);
        assertEquals("30", service.mine(1L, 1).items().getFirst().draftId());
    }

    @Test
    void invalidPageClosedAccountAndEmptyInventoryHaveDistinctOutcomes() {
        assertThrows(ClientException.class, () -> service.mine(1L, 0));
        assertThrows(ClientException.class, () -> service.mine(1L, 10001));
        assertThrows(ClientException.class, () -> service.mine(2L, 1));
        assertTrue(service.mine(1L, 1).items().isEmpty());
        verify(query, never()).selectOwnedPage(any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void unpublishReturnsAdvancedRevisionAndAuditsExactAuthorReason() throws Exception {
        when(skills.update(any(), any())).thenReturn(1);
        assertEquals(1L, service.unpublish(1L, 20L, input, KEY));
        ArgumentCaptor<SkillAuditRecordDO> audit = ArgumentCaptor.forClass(SkillAuditRecordDO.class);
        verify(audits).insert(audit.capture());
        assertEquals(1L, audit.getValue().getActorId());
        assertEquals(20L, audit.getValue().getTargetId());
        assertEquals("作者主动下架", mapper.readTree(audit.getValue().getDetailsJson()).get("reason").asText());
        verify(commands).record(1L, "UNPUBLISH", KEY, List.of(20L, input), 20L, 1L);
    }

    @Test
    void originalReceiptReplaysAfterUnpublishWithoutRepeatingMutation() {
        skill.setStatus("OWNER_UNPUBLISHED");
        when(commands.replay(1L, "UNPUBLISH", KEY, List.of(20L, input), Long.class)).thenReturn(1L);
        assertEquals(1L, service.unpublish(1L, 20L, input, KEY));
        verify(skills, never()).update(any(), any());
        verify(audits, never()).insert(any(SkillAuditRecordDO.class));
    }

    @Test
    void invalidStaleOrPlatformDisabledRequestCannotCreateSuccessfulReceipt() {
        assertThrows(ClientException.class, () -> service.unpublish(1L, 20L, null, KEY));
        assertThrows(ClientException.class,
                () -> service.unpublish(1L, 20L, new SkillUnpublishReqDTO(0L, ""), KEY));
        skill.setStatus("PLATFORM_DISABLED");
        assertThrows(ClientException.class, () -> service.unpublish(1L, 20L, input, KEY));
        skill.setStatus("ACTIVE");
        skill.setRevision(1L);
        assertThrows(ClientException.class, () -> service.unpublish(1L, 20L, input, KEY));
        skill.setRevision(0L);
        when(skills.update(any(), any())).thenReturn(0);
        assertThrows(ClientException.class, () -> service.unpublish(1L, 20L, input, KEY));
        verify(audits, never()).insert(any(SkillAuditRecordDO.class));
        verify(commands, never()).record(any(), any(), any(), any(), any(), any());
    }
}
