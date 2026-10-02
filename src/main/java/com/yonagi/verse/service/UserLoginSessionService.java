package com.yonagi.verse.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.api.DomainEventPublisher;
import com.yonagi.verse.async.event.LoginLogEvent;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.DeviceUtil;
import com.yonagi.verse.common.util.GeoIpUtil;
import com.yonagi.verse.dao.entity.UserDO;
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

    public UserLoginRespDTO create(UserDO user, String source, HttpServletRequest request) {
        return create(user, source, request, tenants.resolveCurrentTenant(user.getUserId()));
    }

    public UserLoginRespDTO create(UserDO user, String source, HttpServletRequest request, CurrentTenantState tenant) {
        return locks.withUser(user.getUserId(), () -> createLocked(user, source, request, tenant));
    }

    private UserLoginRespDTO createLocked(UserDO user, String source, HttpServletRequest request, CurrentTenantState tenant) {
        if (!Integer.valueOf(1).equals(user.getStatus())) throw new ClientException(UserErrorCodeEnum.USER_ACCOUNT_BANNED);
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
        UserLoginRespDTO result = BeanUtil.copyProperties(user, UserLoginRespDTO.class);
        result.setToken(token).setExpiresAt(expires);
        if (tenant.isValid()) result.setCurrentTenant(new UserLoginRespDTO.TenantInfo().setTenantId(tenant.getTenantId())
                .setName(tenant.getName()).setType(tenant.getType()).setRole(tenant.getRole()));
        return result;
    }
}
