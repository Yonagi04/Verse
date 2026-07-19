package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.SensitiveUtil;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.entity.UserTenantDO;
import com.yonagi.verse.dao.mapper.TenantMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.mapper.UserTenantMapper;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.UserService;
import jakarta.validation.constraints.Digits;
import lombok.RequiredArgsConstructor;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Time;
import java.util.Date;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/05/18 19:40
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, UserDO> implements UserService {

    private final PasswordEncoder passwordEncoder;
    private final TenantMapper tenantMapper;
    private final UserTenantMapper userTenantMapper;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final RBloomFilter<String> usernameBloomFilter;
    private final RBloomFilter<String> phoneBloomFilter;
    private final RedissonClient redissonClient;

    @Lazy
    @Autowired
    private UserServiceImpl self;

    @Override
    public Boolean hasUsername(String username) {
        return usernameBloomFilter.contains(username);
    }

    @Override
    public UserRegisterRespDTO register(UserRegisterReqDTO requestParam) {
        if (hasUsername(requestParam.getUsername())) {
            throw new ClientException(UserErrorCodeEnum.USERNAME_EXIST);
        }
        long emailBindCount = getEmailBindCount(requestParam.getEmail());
        if (emailBindCount >= 3) {
            throw new ClientException(UserErrorCodeEnum.EMAIL_BIND_COUNT_EXCEED);
        }
        if (hasPhone(requestParam.getPhone())) {
            throw new ClientException(UserErrorCodeEnum.USER_PHONE_EXIST);
        }

        RLock userRegisterLock = redissonClient.getLock(RedisKeyConstant.LOCK_USER_REGISTER_KEY + requestParam.getUsername());

        try {
            if (userRegisterLock.tryLock(3, 30, TimeUnit.SECONDS)) {
                try {
                    // double-check：防止锁外校验与锁内操作之间的竞态窗口
                    if (hasUsername(requestParam.getUsername())
                            || baseMapper.selectCount(Wrappers.lambdaQuery(UserDO.class)
                                    .eq(UserDO::getUsername, requestParam.getUsername())) > 0) {
                        throw new ClientException(UserErrorCodeEnum.USERNAME_EXIST);
                    }

                    // 通过代理调用以触发 @Transactional
                    UserDO userDO = self.realRegister(requestParam);

                    usernameBloomFilter.add(requestParam.getUsername());
                    phoneBloomFilter.add(requestParam.getPhone());

                    UserRegisterRespDTO resp = new UserRegisterRespDTO();
                    resp.setUserId(userDO.getUserId());
                    resp.setUsername(requestParam.getUsername());
                    resp.setNickname(userDO.getNickname());
                    return resp;
                } finally {
                    userRegisterLock.unlock();
                }
            } else {
                throw new ClientException(UserErrorCodeEnum.USERNAME_EXIST);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Register lock interrupted for username: {}", requestParam.getUsername(), e);
            throw new ServerException(UserErrorCodeEnum.THREAD_INTERRUPTED);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected UserDO realRegister(UserRegisterReqDTO requestParam) {
        Long userId = SnowflakeIdUtil.nextId();
        String encodedPassword = passwordEncoder.encode(requestParam.getPassword());

        UserDO userDO = new UserDO();
        BeanUtil.copyProperties(requestParam, userDO);
        // 昵称默认使用用户名
        if (userDO.getNickname() == null || StrUtil.isBlank(userDO.getNickname())) {
            userDO.setNickname(requestParam.getUsername());
        }
        userDO.setUserId(userId);
        userDO.setPassword(encodedPassword);
        userDO.setStatus(1);
        userDO.setDelFlag(0);

        int inserted = baseMapper.insert(userDO);
        if (inserted < 1) {
            throw new ServerException(UserErrorCodeEnum.USER_SAVED_ERROR);
        }

        // TODO 租户类的Service实现迁移到TenantServiceImpl
        Long tenantId = SnowflakeIdUtil.nextId();
        TenantDO tenantDO = new TenantDO();
        tenantDO.setTenantId(tenantId);
        tenantDO.setName(requestParam.getUsername() + "的个人空间");
        tenantDO.setType("PERSONAL");
        tenantDO.setOwnerId(userId);
        tenantDO.setStatus(1);
        tenantDO.setDelFlag(0);
        int tenantInserted = tenantMapper.insert(tenantDO);
        if (tenantInserted < 1) {
            throw new ServerException(UserErrorCodeEnum.USER_SAVED_ERROR);
        }

        //  TODO 租户类的Service实现迁移到TenantServiceImpl，建立用户-租户关联（SUPER_ADMIN 角色）
        UserTenantDO userTenantDO = new UserTenantDO();
        userTenantDO.setUserId(userId);
        userTenantDO.setTenantId(tenantId);
        userTenantDO.setRole(RoleEnum.SUPER_ADMIN.name());
        userTenantDO.setJoinedAt(new Date());
        int userTenantInserted = userTenantMapper.insert(userTenantDO);
        if (userTenantInserted < 1) {
            throw new ServerException(UserErrorCodeEnum.USER_SAVED_ERROR);
        }

        // TODO 租户类的Service实现迁移到TenantServiceImpl，设置用户的活跃租户
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUserId, userId)
                .set(UserDO::getLastActiveTenantId, tenantId);
        baseMapper.update(null, updateWrapper);

        return userDO;
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername());
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        // 从 Redis 获取会话信息，如果存在则说明用户已登录
        String sessionJson = stringRedisTemplate.opsForValue()
                .get(RedisKeyConstant.USER_LOGIN_KEY + userDO.getUserId());
        if (sessionJson != null) {
            throw new ClientException(UserErrorCodeEnum.USER_HAS_BEEN_LOGIN);
        }
        if (!passwordEncoder.matches(requestParam.getPassword(), userDO.getPassword())) {
            throw new ClientException(UserErrorCodeEnum.PASSWORD_ERROR);
        }
        if (userDO.getStatus() != null && userDO.getStatus() == 0) {
            throw new ClientException(UserErrorCodeEnum.USER_STATUS_DISABLED);
        }

        // 生成 JWT Token
        String token = jwtUtil.generateToken(userDO.getUserId(), userDO.getUsername());
        Date expiresAt = new Date(System.currentTimeMillis() + 86400000);

        // 会话信息存入 Redis
        long ttl = expiresAt.getTime() - System.currentTimeMillis();
        LoginSessionVO session = LoginSessionVO.builder()
                .userId(userDO.getUserId())
                .username(userDO.getUsername())
                .token(token)
                .expiresAt(expiresAt)
                .lastActiveTenantId(userDO.getLastActiveTenantId())
                .loginTime(new Date())
                .build();
        stringRedisTemplate.opsForValue().set(
                RedisKeyConstant.USER_LOGIN_KEY + userDO.getUserId(),
                JSON.toJSONString(session),
                ttl, TimeUnit.MILLISECONDS);

        String tokenHash = DigestUtil.md5Hex(token);
        stringRedisTemplate.opsForValue().set(
                RedisKeyConstant.USER_LOGIN_TOKEN_KEY + tokenHash,
                userDO.getUserId().toString(),
                ttl, TimeUnit.MILLISECONDS);

        UserLoginRespDTO resp = new UserLoginRespDTO();
        resp.setUserId(userDO.getUserId());
        resp.setUsername(userDO.getUsername());
        resp.setNickname(userDO.getNickname());
        resp.setToken(token);
        resp.setExpiresAt(expiresAt);

        // 查询当前活跃租户
        if (userDO.getLastActiveTenantId() != null) {
            TenantDO tenantDO = tenantMapper.selectOne(
                    Wrappers.lambdaQuery(TenantDO.class)
                            .eq(TenantDO::getTenantId, userDO.getLastActiveTenantId()));
            if (tenantDO != null) {
                UserTenantDO userTenantDO = userTenantMapper.selectOne(
                        Wrappers.lambdaQuery(UserTenantDO.class)
                                .eq(UserTenantDO::getUserId, userDO.getUserId())
                                .eq(UserTenantDO::getTenantId, userDO.getLastActiveTenantId())
                                .isNull(UserTenantDO::getLeftAt));
                String role = userTenantDO != null ? userTenantDO.getRole() : null;

                resp.setCurrentTenant(new UserLoginRespDTO.TenantInfo()
                        .setTenantId(tenantDO.getTenantId())
                        .setName(tenantDO.getName())
                        .setType(tenantDO.getType())
                        .setRole(role));
            }
        }

        return resp;
    }

    @Override
    public UserRespDTO getCurrentUser(Long userId, boolean mask) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        UserRespDTO result = new UserRespDTO();
        BeanUtil.copyProperties(userDO, result);
        if (mask) {
            result.setPhone(SensitiveUtil.maskPhone(result.getPhone()));
            result.setEmail(SensitiveUtil.maskEmail(result.getEmail()));
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateProfile(Long userId, UserUpdateReqDTO requestParam) {
        // 校验
        Long emailBindCount = getEmailBindCount(requestParam.getEmail());
        if (emailBindCount >= 3) {
            throw new ClientException(UserErrorCodeEnum.EMAIL_BIND_COUNT_EXCEED);
        }

        // 更新
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUserId, userId);
        UserDO user = BeanUtil.toBean(requestParam, UserDO.class);
        if (StrUtil.isBlank(user.getNickname())) {
            user.setNickname(null);
        }
        int update = baseMapper.update(user, updateWrapper);
        return update > 0;
    }

    @Override
    public Boolean logout(Long userId) {
        // 从 Redis 获取会话信息
        String sessionJson = stringRedisTemplate.opsForValue()
                .get(RedisKeyConstant.USER_LOGIN_KEY + userId);
        if (sessionJson != null) {
            LoginSessionVO session = JSON.parseObject(sessionJson, LoginSessionVO.class);
            // 删除 Token 反向索引
            String tokenHash = DigestUtil.md5Hex(session.getToken());
            stringRedisTemplate.delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + tokenHash);
        }
        // 删除会话 Key
        stringRedisTemplate.delete(RedisKeyConstant.USER_LOGIN_KEY + userId);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePassword(Long userId, UserUpdatePasswordReqDTO requestParam) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        if (passwordEncoder.matches(requestParam.getPassword(), userDO.getPassword())) {
            throw new ClientException(UserErrorCodeEnum.PASSWORD_MATCHED);
        }
        String encodedNewPassword = passwordEncoder.encode(requestParam.getPassword());
        requestParam.setPassword(encodedNewPassword);
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUserId, userId);
        int update = baseMapper.update(BeanUtil.toBean(requestParam, UserDO.class), updateWrapper);
        return update > 0;
    }

    @Override
    public Boolean sendingPhoneCode(UserSendingPhoneCodeReqDTO requestParam) {
        if (!hasPhone(requestParam.getPhone())) {
            throw new ClientException(UserErrorCodeEnum.USER_PHONE_NOT_EXIST);
        }
        // 校验是否发送过于频繁（60 秒间隔，按手机号区分用户）
        String rateKey = RedisKeyConstant.USER_PHONE_SENDING_CODE_KEY + "rate:" + requestParam.getPhone();
        Boolean isAbsent = stringRedisTemplate.opsForValue().setIfAbsent(rateKey, "1", 60, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(isAbsent)) {
            throw new ClientException(UserErrorCodeEnum.USER_PHONE_CODE_SEND_FREQUENT);
        }

        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
        // 保存验证码到 Redis，5 分钟有效
        String codeKey = RedisKeyConstant.USER_PHONE_SENDING_CODE_KEY + requestParam.getPhone();
        stringRedisTemplate.opsForValue().set(codeKey, code, 5, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public UserVerifyPhoneCodeRespDTO verifyCode(UserVerifyPhoneCodeReqDTO requestParam) {
        String phone = requestParam.getPhone();
        String codeKey = RedisKeyConstant.USER_PHONE_SENDING_CODE_KEY + phone;
        String storedCode = stringRedisTemplate.opsForValue().get(codeKey);
        if (storedCode == null || !storedCode.equals(requestParam.getCode())) {
            throw new ClientException(UserErrorCodeEnum.USER_PHONE_CODE_ERROR);
        }
        // 验证成功后删除验证码
        stringRedisTemplate.delete(codeKey);

        String token = jwtUtil.generateResetPasswordToken(phone, requestParam.getCode());
        String tokenHash = DigestUtil.md5Hex(token);
        stringRedisTemplate.opsForValue().set(RedisKeyConstant.USER_RESET_PHONE_TOKEN_KEY + phone,
                tokenHash, 10, TimeUnit.MINUTES);
        UserVerifyPhoneCodeRespDTO resp = new UserVerifyPhoneCodeRespDTO();
        resp.setToken(token);
        return resp;
    }

    @Override
    public Boolean resetPassword(UserResetPasswordReqDTO requestParam) {
        String tokenHash = DigestUtil.md5Hex(requestParam.getToken());
        String phoneKey = RedisKeyConstant.USER_RESET_PHONE_TOKEN_KEY + requestParam.getPhone();
        String storedTokenHash = stringRedisTemplate.opsForValue().get(phoneKey);
        if (storedTokenHash == null || !storedTokenHash.equals(tokenHash)) {
            throw new ClientException(UserErrorCodeEnum.USER_RESET_PASSWORD_FAIL);
        }

        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getPhone, requestParam.getPhone());
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        UserUpdatePasswordReqDTO dto = new UserUpdatePasswordReqDTO();
        dto.setPassword(requestParam.getPassword());
        self.updatePassword(userDO.getUserId(), dto);

        // 密码更新成功后才删除 token，防止重复使用
        stringRedisTemplate.delete(phoneKey);
        return true;
    }

    private Long getEmailBindCount(String email) {
        // TODO 用Redis的Set维护一个邮箱绑定的用户ID集合，直接查询 SCARD 集合的大小即可
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getEmail, email);
        return baseMapper.selectCount(queryWrapper);
    }

    public Boolean hasPhone(String phone) {
        return phoneBloomFilter.contains(phone);
    }
}