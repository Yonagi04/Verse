package com.yonagi.verse.common.security;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    @Test void resetTokensForSamePhoneAndCodeHaveDistinctIdentifiers() {
        var jwt = new JwtUtil("reset-test-secret-key-with-at-least-32-bytes", 600_000);
        var tokens = new HashSet<String>(); var identifiers = new HashSet<String>();
        for (int n = 0; n < 100; n++) {
            var token = jwt.generateResetPasswordToken("13800138000", "123456");
            var claims = jwt.parseToken(token);
            assertEquals("13800138000", claims.getSubject()); assertEquals("123456", claims.get("code"));
            assertNotNull(claims.getId()); assertTrue(tokens.add(token)); assertTrue(identifiers.add(claims.getId()));
        }
    }
}
