package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.common.config.MessagingProperties;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.support.UserUsageTestFixtures;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserCostAlertChannelReadinessServiceTest {
    @Test void channelReadinessUsesOnlySelfContactAndDoesNotPretendProviderReachability() {
        var properties=new MessagingProperties(); var aes=mock(AesUtil.class); var service=new UserCostAlertChannelReadinessService(properties,aes); var user=UserUsageTestFixtures.user();
        user.setEmail("email-cipher"); user.setPhone("phone-cipher"); user.setPhoneHash("phone-hash");
        when(aes.decrypt("email-cipher")).thenReturn("alice@example.test"); when(aes.decrypt("phone-cipher")).thenReturn("13800138000");
        assertTrue(service.readiness(user).getFirst().available()); assertEquals("CHANNEL_DISABLED",service.readiness(user).get(1).reasonCode());
        properties.getSms().setProvider("sms-dev"); properties.getEmail().setProvider("mailpit");
        assertEquals("TEMPLATE_MISSING",service.readiness(user).get(1).reasonCode());
        properties.getSms().setPersonalCostWarningTemplateCode("personal-template"); properties.getEmail().setAccountName("sender@example.test");
        var ready=service.readiness(user); assertTrue(ready.stream().allMatch(c->c.available()));
        assertEquals("138****8000",ready.get(1).contactMasked()); assertEquals("a***@example.test",ready.get(2).contactMasked());
        assertEquals("phone-hash",service.hash(user,"SMS")); assertEquals("hash",service.hash(user,"EMAIL"));
        user.setPhone(null); assertEquals("CONTACT_MISSING",service.readiness(user).get(1).reasonCode());
        when(aes.decrypt("email-cipher")).thenReturn("invalid"); assertEquals("CONTACT_INVALID",service.readiness(user).get(2).reasonCode());
        when(aes.decrypt("email-cipher")).thenThrow(new IllegalStateException("encryption unavailable")); assertEquals("STATE_UNAVAILABLE",service.readiness(user).get(2).reasonCode());
    }
}
