package com.yonagi.verse.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class JwtUtilTest {
    private static final String SECRET = "reset-test-secret-key-with-at-least-32-bytes";

    @Test void resetTokensForSamePhoneAndCodeHaveDistinctIdentifiers() {
        var jwt = new JwtUtil(SECRET, 600_000);
        var tokens = new HashSet<String>(); var identifiers = new HashSet<String>();
        for (int n = 0; n < 100; n++) {
            var token = jwt.generateResetPasswordToken("13800138000", "123456");
            var claims = jwt.parseToken(token);
            assertEquals("13800138000", claims.getSubject()); assertEquals("123456", claims.get("code"));
            assertNotNull(claims.getId()); assertTrue(tokens.add(token)); assertTrue(identifiers.add(claims.getId()));
        }
    }

    @Test
    void concurrentResetTokensRemainValidAndUnique() throws Exception {
        JwtUtil jwt = new JwtUtil(SECRET, 600_000);
        int workers = 8;
        int tokensPerWorker = 16;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers), Thread.ofPlatform().name("jwt-test-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        try (pool) {
            List<Future<List<String>>> results = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    List<String> tokens = new ArrayList<>();
                    for (int index = 0; index < tokensPerWorker; index++) {
                        String token = jwt.generateResetPasswordToken("13800138000", "123456");
                        Claims claims = jwt.parseToken(token);
                        assertEquals("13800138000", claims.getSubject());
                        assertEquals("123456", claims.get("code"));
                        assertNotNull(claims.getId());
                        tokens.add(token);
                    }
                    return tokens;
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            Set<String> tokens = new HashSet<>();
            Set<String> identifiers = new HashSet<>();
            for (Future<List<String>> result : results) {
                for (String token : result.get(10, TimeUnit.SECONDS)) {
                    assertTrue(tokens.add(token), "并发签发不能生成重复 Token");
                    assertTrue(identifiers.add(jwt.parseToken(token).getId()), "并发签发不能复用 jti");
                }
            }
            assertEquals(workers * tokensPerWorker, tokens.size());
        } finally {
            start.countDown();
        }
    }

    @Test
    void expiredTokensAreRejected() {
        JwtUtil jwt = new JwtUtil(SECRET, 600_000);
        String expired = new JwtUtil(SECRET, -60_000).generateResetPasswordToken("13800138000", "123456");
        assertThrows(ExpiredJwtException.class, () -> jwt.parseToken(expired));
        assertFalse(jwt.validateToken(expired));
        assertTrue(jwt.isTokenExpired(expired));
    }

    @Test
    void wrongKeysTamperedSignaturesAndMalformedTokensAreRejected() {
        JwtUtil jwt = new JwtUtil(SECRET, 600_000);
        String valid = jwt.generateResetPasswordToken("13800138000", "123456");
        JwtUtil wrongKey = new JwtUtil("wrong-test-secret-key-with-at-least-32-bytes", 600_000);
        assertFalse(wrongKey.validateToken(valid));
        int signatureStart = valid.lastIndexOf('.') + 1;
        char replacement = valid.charAt(signatureStart) == 'A' ? 'B' : 'A';
        String tampered = valid.substring(0, signatureStart) + replacement + valid.substring(signatureStart + 1);
        assertFalse(jwt.validateToken(tampered));
        assertFalse(jwt.validateToken("not-a-jwt"));
        assertTrue(jwt.validateToken(valid));
    }
}
