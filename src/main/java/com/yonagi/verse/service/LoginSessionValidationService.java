package com.yonagi.verse.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;

/** HTTP 与长连接共用的登录会话规则；账号和撤销状态均实时读取。 */
@Service
@RequiredArgsConstructor
public class LoginSessionValidationService {
    private final JwtUtil jwt;
    private final StringRedisTemplate redis;
    private final AccountAvailabilityService accounts;

    /**
     * 验证签名、登录用途、有效期及当前会话状态，不接受密码重置或管理确认令牌。
     *
     * @param token 接入层提取的 Bearer 凭证
     * @return 不包含原始凭证的已认证身份
     * @throws ClientException 会话无效或账号不可用
     */
    public Identity authenticate(String token) {
        Identity identity;
        try {
            identity = parseIdentity(token);
        } catch (ExpiredJwtException exception) {
            throw new ClientException(BaseErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException exception) {
            // JWT 解析错误可能携带输入片段，不能交给 HTTP/WS 默认错误处理直接展示。
            throw new ClientException(BaseErrorCode.TOKEN_INVALID);
        }
        requireActive(identity);
        return identity;
    }

    private Identity parseIdentity(String token) {
        Claims claims = jwt.parseToken(token);
        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("username", String.class);
        String sessionId = claims.getId();
        Date expiration = claims.getExpiration();
        if (userId <= 0 || !userId.toString().equals(claims.getSubject())
                || !StringUtils.hasText(username) || !StringUtils.hasText(sessionId) || expiration == null) {
            throw new ClientException(BaseErrorCode.TOKEN_INVALID);
        }
        return new Identity(userId, username, sessionId, DigestUtil.md5Hex(token), expiration.getTime());
    }

    /**
     * 复核既有身份，用于每次长连接入站操作及业务消息投递；依赖失败直接拒绝。
     *
     * @param identity 首次认证后由服务器保存的身份，不能来自客户端消息头
     * @throws ClientException 到期、撤销或账号失效
     */
    public void requireActive(Identity identity) {
        if (identity.expiresAtMillis() <= System.currentTimeMillis()) {
            throw new ClientException(BaseErrorCode.TOKEN_EXPIRED);
        }
        String indexedUser = redis.opsForValue().get(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + identity.tokenHash());
        if (!identity.userId().toString().equals(indexedUser) || !accounts.isAccountActive(identity.userId())) {
            throw new ClientException(BaseErrorCode.TOKEN_INVALID);
        }
    }

    /** 服务器内部会话身份；不持有原始令牌，不能用于新会话签发。 */
    public record Identity(Long userId, String username, String sessionId, String tokenHash, long expiresAtMillis) {
    }
}
