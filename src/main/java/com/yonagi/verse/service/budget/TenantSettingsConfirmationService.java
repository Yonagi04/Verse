package com.yonagi.verse.service.budget;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.CostBudgetErrorCodeEnum;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.SecretKey;

/** 独立用途和密钥，凭证只证明用户已看到同一内容和影响，不承担身份认证。 */
@Service
public class TenantSettingsConfirmationService {
    private final String secret;
    private final BudgetPeriodResolver time;
    public TenantSettingsConfirmationService(@Value("${verse.security.tenant-settings-confirmation.secret:}") String secret,
            BudgetPeriodResolver time) { this.secret = secret; this.time = time; }
    public record Proof(/** 签名凭证。 */ String token, /** 到期时间。 */ Instant expiresAt) { }
    private SecretKey key() {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new ServerException("租户设置确认密钥未配置", CostBudgetErrorCodeEnum.UNAVAILABLE);
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
    public Proof issue(Long userId, Long tenantId, String settingsVersion, String costVersion, String payload, String impact) {
        Instant now = time.now(), expires = now.plusSeconds(300);
        String token = Jwts.builder().subject(String.valueOf(tenantId)).claim("purpose", "TENANT_COST_SETTINGS")
                .claim("userId", String.valueOf(userId)).claim("settingsVersion", settingsVersion).claim("costConfigVersion", costVersion)
                .claim("payloadDigest", digest(payload)).claim("impactDigest", digest(impact)).id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now)).expiration(Date.from(expires)).signWith(key()).compact();
        return new Proof(token, expires);
    }
    /** 过期但签名合法的凭证可刷新；篡改、用途、主体或内容变化直接拒绝。 */
    public boolean verify(String token, Long userId, Long tenantId, String settingsVersion, String costVersion, String payload, String impact) {
        Claims claims; boolean expired = false;
        try {
            claims = Jwts.parser().verifyWith(key()).clock(() -> Date.from(time.now())).build().parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) { claims = e.getClaims(); expired = true;
        } catch (JwtException | IllegalArgumentException e) { throw invalid(); }
        if (!"TENANT_COST_SETTINGS".equals(claims.get("purpose")) || !String.valueOf(tenantId).equals(claims.getSubject())
                || !String.valueOf(userId).equals(claims.get("userId")) || !digest(payload).equals(claims.get("payloadDigest"))
                || !(claims.get("settingsVersion") instanceof String) || !(claims.get("costConfigVersion") instanceof String)
                || !(claims.get("impactDigest") instanceof String) || claims.getExpiration() == null || claims.getIssuedAt() == null
                || claims.getId() == null) throw invalid();
        return !expired && settingsVersion.equals(claims.get("settingsVersion")) && costVersion.equals(claims.get("costConfigVersion"))
                && digest(impact).equals(claims.get("impactDigest"));
    }
    private static String digest(String value) { return DigestUtil.sha256Hex(value); }
    private static ClientException invalid() { return new ClientException(CostBudgetErrorCodeEnum.CONFIRMATION_INVALID); }
}
