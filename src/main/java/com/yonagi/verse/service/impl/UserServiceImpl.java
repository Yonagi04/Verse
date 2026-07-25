package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.UserErrorCodeEnum;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.common.util.SensitiveUtil;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.TenantService;
import com.yonagi.verse.service.UserService;
import com.yonagi.verse.service.UserTenantService;
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

import java.util.Date;
import java.util.Objects;
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
    private final AesUtil aesUtil;
    private final TenantService tenantService;
    private final UserTenantService userTenantService;
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

                    String phoneHash = aesUtil.hashForLookup(requestParam.getPhone());
                    String emailHash = aesUtil.hashForLookup(requestParam.getEmail());
                    // 通过代理调用以触发 @Transactional
                    UserDO userDO = self.realRegister(requestParam, phoneHash, emailHash);

                    usernameBloomFilter.add(requestParam.getUsername());
                    phoneBloomFilter.add(phoneHash);
                    stringRedisTemplate.opsForSet().add(RedisKeyConstant.USER_EMAIL_COUNT_KEY + emailHash,
                            userDO.getUserId().toString());

                    UserRegisterRespDTO resp = new UserRegisterRespDTO();
                    BeanUtil.copyProperties(userDO, resp);
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
    protected UserDO realRegister(UserRegisterReqDTO requestParam, String phoneHash, String emailHash) {
        Long userId = SnowflakeIdUtil.nextId();
        String encodedPassword = passwordEncoder.encode(requestParam.getPassword());

        String encryptedEmail = aesUtil.encrypt(requestParam.getEmail());
        String encryptedPhone = aesUtil.encrypt(requestParam.getPhone());

        UserDO userDO = new UserDO();
        userDO.setUsername(requestParam.getUsername());
        userDO.setNickname(requestParam.getNickname());
        // 昵称默认使用用户名
        if (userDO.getNickname() == null || StrUtil.isBlank(userDO.getNickname())) {
            userDO.setNickname(requestParam.getUsername());
        }
        userDO.setUserId(userId);
        userDO.setPassword(encodedPassword);
        userDO.setEmail(encryptedEmail);
        userDO.setEmailHash(emailHash);
        userDO.setPhone(encryptedPhone);
        userDO.setPhoneHash(phoneHash);
        userDO.setStatus(1);

        int inserted = baseMapper.insert(userDO);
        if (inserted < 1) {
            throw new ServerException(UserErrorCodeEnum.USER_SAVED_ERROR);
        }

        Long tenantId = tenantService.createPersonalTenant(userId,
                requestParam.getUsername() + "的个人空间");

        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUserId, userId)
                .set(UserDO::getLastActiveTenantId, tenantId);
        baseMapper.update(updateWrapper);

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
        BeanUtil.copyProperties(userDO, resp);
        resp.setToken(token);
        resp.setExpiresAt(expiresAt);

        if (userDO.getLastActiveTenantId() != null) {
            TenantInfoRespDTO tenantInfoResp = tenantService.getTenantInfo(userDO.getLastActiveTenantId());
            if (tenantInfoResp != null) {
                String role = userTenantService.getRoleByUserIdAndTenantId(userDO.getUserId(), tenantInfoResp.getTenantId());
                resp.setCurrentTenant(new UserLoginRespDTO.TenantInfo()
                        .setTenantId(tenantInfoResp.getTenantId())
                        .setName(tenantInfoResp.getName())
                        .setType(tenantInfoResp.getType())
                        .setRole(role));
            }
        }

        return resp;
    }

    @Override
    public UserRespDTO getCurrentUser(Long userId, boolean mask) {
        String cacheKey = RedisKeyConstant.USER_PROFILE_KEY + userId;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            UserRespDTO result = JSON.parseObject(cachedJson, UserRespDTO.class);
            if (mask) {
                result.setPhone(SensitiveUtil.maskPhone(result.getPhone()));
                result.setEmail(SensitiveUtil.maskEmail(result.getEmail()));
            }
            return result;
        }

        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        // 解密敏感字段
        userDO.setEmail(aesUtil.decrypt(userDO.getEmail()));
        userDO.setPhone(aesUtil.decrypt(userDO.getPhone()));

        UserRespDTO result = new UserRespDTO();
        BeanUtil.copyProperties(userDO, result);
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(result), 1, TimeUnit.HOURS);

        if (mask) {
            result.setPhone(SensitiveUtil.maskPhone(result.getPhone()));
            result.setEmail(SensitiveUtil.maskEmail(result.getEmail()));
        }
        return result;
    }

    @Override
    public UserInfoRespDTO getUserInfo(Long userId) {
        String cacheKey = RedisKeyConstant.USER_ANOTHER_PROFILE_KEY + userId;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            return JSON.parseObject(cachedJson, UserInfoRespDTO.class);
        }

        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId);
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        UserInfoRespDTO result = new UserInfoRespDTO();
        BeanUtil.copyProperties(userDO, result);
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(result), 1, TimeUnit.HOURS);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateProfile(Long userId, UserUpdateReqDTO requestParam) {
        // 查询当前用户记录，用于判断邮箱/手机号是否变更
        UserDO currentUser = baseMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId));
        if (currentUser == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }

        // 解密当前值用于比对
        String oldEmail = aesUtil.decrypt(currentUser.getEmail());
        String oldEmailHash = currentUser.getEmailHash();
        String oldPhone = aesUtil.decrypt(currentUser.getPhone());
        String newEmail = requestParam.getEmail();
        String newPhone = requestParam.getPhone();
        boolean emailChanged = !newEmail.equals(oldEmail);
        boolean phoneChanged = !newPhone.equals(oldPhone);

        // 邮箱变更时，校验新邮箱绑定数是否已达上限
        if (emailChanged) {
            Long emailBindCount = getEmailBindCount(newEmail);
            if (emailBindCount >= 3) {
                throw new ClientException(UserErrorCodeEnum.EMAIL_BIND_COUNT_EXCEED);
            }
        }

        // 更新数据库
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUserId, userId);

        // 处理昵称
        if (StrUtil.isNotBlank(requestParam.getNickname())) {
            updateWrapper.set(UserDO::getNickname, requestParam.getNickname());
        } else if (requestParam.getNickname() != null) {
            // 显式传入空字符串视为清空昵称
            updateWrapper.set(UserDO::getNickname, null);
        }

        // 处理邮箱变更
        if (emailChanged) {
            String encryptedEmail = aesUtil.encrypt(newEmail);
            String newEmailHash = aesUtil.hashForLookup(newEmail);
            updateWrapper.set(UserDO::getEmail, encryptedEmail);
            updateWrapper.set(UserDO::getEmailHash, newEmailHash);
        }

        // 处理手机号变更
        if (phoneChanged) {
            String encryptedPhone = aesUtil.encrypt(newPhone);
            String newPhoneHash = aesUtil.hashForLookup(newPhone);
            updateWrapper.set(UserDO::getPhone, encryptedPhone);
            updateWrapper.set(UserDO::getPhoneHash, newPhoneHash);
        }

        int update = baseMapper.update(null, updateWrapper);
        if (update < 0) {
            log.error("Failed to update user profile for userId: {}, requestParam: {}", userId, requestParam);
            throw new ServerException(UserErrorCodeEnum.USER_UPDATE_ERROR);
        }

        // DB 更新成功后，删除用户信息缓存
        stringRedisTemplate.delete(RedisKeyConstant.USER_PROFILE_KEY + userId);
        stringRedisTemplate.delete(RedisKeyConstant.USER_ANOTHER_PROFILE_KEY + userId);

        // 邮箱变更时，维护 Redis Set：旧邮箱移除绑定，新邮箱添加绑定
        if (emailChanged) {
            String newEmailHash = aesUtil.hashForLookup(newEmail);
            stringRedisTemplate.opsForSet().remove(RedisKeyConstant.USER_EMAIL_COUNT_KEY + oldEmailHash,
                    userId.toString());
            stringRedisTemplate.opsForSet().add(RedisKeyConstant.USER_EMAIL_COUNT_KEY + newEmailHash,
                    userId.toString());
        }
        // 手机号变更时，维护布隆过滤器（无法删除旧值，只能添加新值）
        if (phoneChanged) {
            String newPhoneHash = aesUtil.hashForLookup(newPhone);
            phoneBloomFilter.add(newPhoneHash);
        }

        return true;
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
        String phoneHash = aesUtil.hashForLookup(requestParam.getPhone());
        // 校验是否发送过于频繁（60 秒间隔，按手机号区分用户）
        String rateKey = RedisKeyConstant.USER_PHONE_SENDING_CODE_KEY + "rate:" + phoneHash;
        Boolean isAbsent = stringRedisTemplate.opsForValue().setIfAbsent(rateKey, "1", 60, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(isAbsent)) {
            throw new ClientException(UserErrorCodeEnum.USER_PHONE_CODE_SEND_FREQUENT);
        }

        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
        // 保存验证码到 Redis，5 分钟有效
        String codeKey = RedisKeyConstant.USER_PHONE_SENDING_CODE_KEY + phoneHash;
        stringRedisTemplate.opsForValue().set(codeKey, code, 5, TimeUnit.MINUTES);
        return true;
    }

    @Override
    public UserVerifyPhoneCodeRespDTO verifyCode(UserVerifyPhoneCodeReqDTO requestParam) {
        String phoneHash = aesUtil.hashForLookup(requestParam.getPhone());
        String codeKey = RedisKeyConstant.USER_PHONE_SENDING_CODE_KEY + phoneHash;
        String storedCode = stringRedisTemplate.opsForValue().get(codeKey);
        if (storedCode == null || !storedCode.equals(requestParam.getCode())) {
            throw new ClientException(UserErrorCodeEnum.USER_PHONE_CODE_ERROR);
        }
        // 验证成功后删除验证码
        stringRedisTemplate.delete(codeKey);

        String token = jwtUtil.generateResetPasswordToken(requestParam.getPhone(), requestParam.getCode());
        String tokenHash = DigestUtil.md5Hex(token);
        stringRedisTemplate.opsForValue().set(RedisKeyConstant.USER_RESET_PHONE_TOKEN_KEY + phoneHash,
                tokenHash, 10, TimeUnit.MINUTES);
        UserVerifyPhoneCodeRespDTO resp = new UserVerifyPhoneCodeRespDTO();
        resp.setToken(token);
        return resp;
    }

    @Override
    public Boolean resetPassword(UserResetPasswordReqDTO requestParam) {
        String phoneHash = aesUtil.hashForLookup(requestParam.getPhone());
        String tokenHash = DigestUtil.md5Hex(requestParam.getToken());
        String phoneKey = RedisKeyConstant.USER_RESET_PHONE_TOKEN_KEY + phoneHash;
        String storedTokenHash = stringRedisTemplate.opsForValue().get(phoneKey);
        if (storedTokenHash == null || !storedTokenHash.equals(tokenHash)) {
            throw new ClientException(UserErrorCodeEnum.USER_RESET_PASSWORD_FAIL);
        }

        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getPhoneHash, phoneHash);
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
        // 用Redis的Set维护一个邮箱绑定的用户ID集合（Key使用邮箱哈希），直接查询集合的大小即可
        String emailHash = aesUtil.hashForLookup(email);
        Long userIdCount = stringRedisTemplate.opsForSet().size(RedisKeyConstant.USER_EMAIL_COUNT_KEY + emailHash);
        return Objects.requireNonNullElse(userIdCount, 0L);
    }

    private Boolean hasPhone(String phone) {
        String phoneHash = aesUtil.hashForLookup(phone);
        return phoneBloomFilter.contains(phoneHash);
    }
}