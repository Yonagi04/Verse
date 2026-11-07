package com.yonagi.verse.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.event.LoginLogEvent;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.common.enums.UserStatusEnum;
import com.yonagi.verse.common.enums.ExternalAuthErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.DeviceUtil;
import com.yonagi.verse.common.util.GeoIpUtil;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.projection.CurrentTenantState;
import com.yonagi.verse.dto.resp.LoginSessionVO;
import com.yonagi.verse.dto.resp.UserLoginRespDTO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 密码和外部认证共用的站内会话签发入口。 */
@Service
@RequiredArgsConstructor
public class UserLoginSessionService {
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate redis;
    private final CurrentTenantStateService tenants;
    private final GeoIpUtil geoIp;
    private final DomainEventPublisher events;
    private final com.yonagi.verse.common.security.UserSecurityLocks locks;
    private final UserMapper users;

    /** 解析当前租户后签发；传入账号必须是调用方已验证凭证的快照，锁内再次复核。 */
    public UserLoginRespDTO create(UserDO user, String source, HttpServletRequest request) {
        return create(user, source, request, tenants.resolveCurrentTenant(user.getUserId()));
    }

    /** 外部认证可传入已解析租户，避免持有用户行锁后重新获取租户锁。 */
    public UserLoginRespDTO create(UserDO user, String source, HttpServletRequest request, CurrentTenantState tenant) {
        return locks.withUser(user.getUserId(), () -> createLocked(user, source, request, tenant));
    }

    private UserLoginRespDTO createLocked(UserDO user, String source, HttpServletRequest request,
                                          CurrentTenantState tenant) {
        // 当前读避免外部登录事务的旧快照；与改密/注销共用安全锁直到索引建立完毕。
        UserDO current = users.selectActiveUserForShare(user.getUserId());
        if (current == null || !UserStatusEnum.USER_STATUS_ACTIVE.getStatusCode().equals(current.getStatus())
                || !Integer.valueOf(0).equals(current.getDelFlag())) {
            throw new ClientException(UserErrorCodeEnum.USER_ACCOUNT_BANNED);
        }
        if ("PASSWORD".equals(source) && (user.getPassword() == null
                || !Objects.equals(user.getPassword(), current.getPassword()))) {
            throw new ClientException(UserErrorCodeEnum.PASSWORD_ERROR);
        }
        user = current;
        String token = jwtUtil.generateToken(user.getUserId(), user.getUsername());
        var claims = jwtUtil.parseToken(token);
        Date expires = claims.getExpiration();
        String ip = DeviceUtil.getClientIp(request);
        String agent = request.getHeader("User-Agent");
        String device = DeviceUtil.generateDeviceId(agent, ip);
        Date now = new Date();
        LoginSessionVO session = LoginSessionVO.builder().userId(user.getUserId()).username(user.getUsername())
                .token(token).expiresAt(expires).sessionId(claims.getId()).loginSource(source)
                .passwordVerifiedAt("PASSWORD".equals(source) ? now : null)
                .lastActiveTenantId(tenant.getTenantId()).loginTime(now).deviceId(device)
                .deviceName(DeviceUtil.parseDeviceName(agent)).ip(ip).region(geoIp.lookupRegion(ip)).build();
        String devicesKey = RedisKeyConstant.USER_DEVICES_KEY + user.getUserId();
        String tokenKey = RedisKeyConstant.USER_LOGIN_TOKEN_KEY + DigestUtil.md5Hex(token);
        long ttl = expires.getTime() - System.currentTimeMillis();
        // 两个会话索引原子写入，同时撤销同设备上次登录的令牌。
        String script = "redis.call('DEL',KEYS[3]); redis.call('HSET',KEYS[1],ARGV[1],ARGV[2]); "
                + "local t=redis.call('PTTL',KEYS[1]); if t<tonumber(ARGV[3]) then redis.call('PEXPIRE',KEYS[1],ARGV[3]) end; "
                + "redis.call('SET',KEYS[2],ARGV[4],'PX',ARGV[3]); return 1";
        // 安全锁下读取旧会话，在同一Lua脚本内撤销旧令牌并建立新索引。
        Object previous = redis.opsForHash().get(devicesKey, device);
        String oldTokenKey = tokenKey + ":previous";
        if (previous != null) {
            LoginSessionVO old = JSON.parseObject(previous.toString(), LoginSessionVO.class);
            oldTokenKey = RedisKeyConstant.USER_LOGIN_TOKEN_KEY + DigestUtil.md5Hex(old.getToken());
        }
        redis.execute(new DefaultRedisScript<>(script, Long.class), List.of(devicesKey, tokenKey, oldTokenKey),
                device, JSON.toJSONString(session), Long.toString(ttl), user.getUserId().toString());
        LoginLogEvent event = new LoginLogEvent();
        event.setUserId(user.getUserId()); event.setDeviceId(device); event.setDeviceName(session.getDeviceName());
        event.setIp(ip); event.setRegion(session.getRegion()); event.setResult("成功");
        event.setLoginSource(source); event.setLoginTime(now); event.setKey(user.getUserId().toString());
        try { events.publishInTx(event); }
        catch (RuntimeException e) {
            redis.delete(tokenKey);
            redis.opsForHash().delete(devicesKey, device);
            throw e;
        }
        UserLoginRespDTO result = new UserLoginRespDTO().setUserId(user.getUserId())
                .setUsername(user.getUsername()).setNickname(user.getNickname());
        result.setToken(token).setExpiresAt(expires);
        if (tenant.isValid()) {
            result.setCurrentTenant(new UserLoginRespDTO.TenantInfo().setTenantId(tenant.getTenantId())
                    .setName(tenant.getName()).setType(tenant.getType()).setRole(tenant.getRole()));
        }
        return result;
    }

    /**
     * 以可信认证指纹识别当前设备，网络/UA 变化不会改变会话身份。
     *
     * @param userId 当前认证账号，不允许代表其他用户查询
     * @return 会话设备；展示索引缺失时为空
     * @throws ClientException 可信上下文或令牌反向索引不匹配
     */
    public Optional<String> currentDeviceId(Long userId) {
        String tokenHash = currentTokenHash(userId);
        requireIndexedUser(userId, tokenHash);
        return findDeviceId(userId, tokenHash);
    }

    /**
     * 先撤销真实凭证，再清理设备展示索引；后续投影失败也不能使凭证复活。
     *
     * @param userId 当前认证账号，调用方须持有用户安全锁
     * @return 被撤销的设备标识，展示索引缺失时为空
     * @throws ClientException 认证上下文或令牌索引不匹配
     */
    public Optional<String> revokeCurrent(Long userId) {
        String tokenHash = currentTokenHash(userId);
        requireIndexedUser(userId, tokenHash);
        redis.delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + tokenHash);
        Optional<String> device = findDeviceId(userId, tokenHash);
        device.ifPresent(id -> redis.opsForHash().delete(RedisKeyConstant.USER_DEVICES_KEY + userId, id));
        return device;
    }

    private String currentTokenHash(Long userId) {
        UserContext context = UserContextHolder.get();
        if (context == null || !Objects.equals(userId, context.getUserId())
                || context.getTokenHash() == null || context.getTokenHash().isBlank()) {
            throw new ClientException(ExternalAuthErrorCodeEnum.VERSE_SESSION_CHANGED);
        }
        return context.getTokenHash();
    }

    private void requireIndexedUser(Long userId, String tokenHash) {
        if (!userId.toString().equals(redis.opsForValue().get(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + tokenHash))) {
            throw new ClientException(ExternalAuthErrorCodeEnum.VERSE_SESSION_CHANGED);
        }
    }

    private Optional<String> findDeviceId(Long userId, String tokenHash) {
        Map<Object, Object> devices = redis.opsForHash().entries(RedisKeyConstant.USER_DEVICES_KEY + userId);
        for (Map.Entry<Object, Object> device : devices.entrySet()) {
            LoginSessionVO session = JSON.parseObject(device.getValue().toString(), LoginSessionVO.class);
            if (session != null && userId.equals(session.getUserId()) && session.getToken() != null
                    && tokenHash.equals(DigestUtil.md5Hex(session.getToken()))) {
                return Optional.of(device.getKey().toString());
            }
        }
        return Optional.empty();
    }
}
