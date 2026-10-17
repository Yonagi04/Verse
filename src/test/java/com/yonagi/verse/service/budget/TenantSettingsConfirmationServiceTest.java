package com.yonagi.verse.service.budget;

import com.yonagi.verse.common.convention.exception.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class TenantSettingsConfirmationServiceTest {
    private final Instant now = Instant.parse("2026-10-17T07:00:00Z");
    private final String secret = "confirmation-only-secret-32-bytes-minimum";
    private TenantSettingsConfirmationService service(Instant time) {
        return new TenantSettingsConfirmationService(secret, new BudgetPeriodResolver(Clock.fixed(time, ZoneOffset.UTC)));
    }
    @Test void proofBindsSubjectPayloadVersionsAndImpact() {
        var service = service(now); var proof = service.issue(1L,2L,"3","4","target","impact");
        assertEquals(now.plusSeconds(300), proof.expiresAt());
        assertTrue(service.verify(proof.token(),1L,2L,"3","4","target","impact"));
        assertFalse(service.verify(proof.token(),1L,2L,"5","4","target","impact"));
        assertFalse(service.verify(proof.token(),1L,2L,"3","5","target","impact"));
        assertFalse(service.verify(proof.token(),1L,2L,"3","4","target","new-impact"));
        assertThrows(ClientException.class, () -> service.verify(proof.token(),9L,2L,"3","4","target","impact"));
        assertThrows(ClientException.class, () -> service.verify(proof.token(),1L,9L,"3","4","target","impact"));
        assertThrows(ClientException.class, () -> service.verify(proof.token(),1L,2L,"3","4","changed","impact"));
    }
    @Test void expirationRefreshesButTamperingOrOtherSigningKeyRejects() {
        var proof = service(now).issue(1L,2L,"3","4","target","impact");
        assertFalse(service(now.plusSeconds(301)).verify(proof.token(),1L,2L,"3","4","target","impact"));
        assertThrows(ClientException.class, () -> service(now).verify("broken",1L,2L,"3","4","target","impact"));
        var other = new TenantSettingsConfirmationService("another-independent-signing-secret-32bytes",new BudgetPeriodResolver());
        assertThrows(ClientException.class, () -> other.verify(proof.token(),1L,2L,"3","4","target","impact"));
    }
    @Test void absentSecretNeverBypassesConfirmation() {
        var service = new TenantSettingsConfirmationService("",new BudgetPeriodResolver());
        assertThrows(ServerException.class, () -> service.issue(1L,2L,"0","0","t","i"));
    }
}
