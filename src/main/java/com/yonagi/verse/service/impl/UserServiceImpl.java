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
import com.yonagi.verse.common.enums.UserStatusEnum;
import com.yonagi.verse.common.security.JwtUtil;
import com.yonagi.verse.common.util.AesUtil;
import com.yonagi.verse.common.util.DeviceUtil;
import com.yonagi.verse.common.util.GeoIpUtil;
import com.yonagi.verse.common.util.SensitiveUtil;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.LoginDeviceDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.entity.UserPrivacyDO;
import com.yonagi.verse.dao.mapper.LoginDeviceMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dao.mapper.UserPrivacyMapper;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;
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

    private static final String CLOSE_ACCOUNT_WARNING_DESCRIPTION = "注销后将永久删除您的账号及相关数据，该操作不可恢复。";
    private static final List<String> CLOSE_ACCOUNT_WARNING_TIPS = List.of(
            "账号将无法登录 Verse",
            "您将退出所有租户",
            "个人版租户将被删除",
            "您创建的 API Key 将全部失效",
            "您注册的 LLM Service 将停止提供服务",
            "历史 Token 统计数据将被清除"
    );
    private static final String CLOSED_ACCOUNT_LOGIN_WARNING = "您的账号已于%s申请并完成了注销，感谢您使用 Verse，祝您生活愉快！";
    private static final DateTimeFormatter CANCEL_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy年MM月dd日");
    private static final String WELCOME_MESSAGE_TITLE = "欢迎使用 Verse！";
    private static final String WELCOME_MESSAGE_CONTENT = "你好, %s！欢迎使用 Verse！";
    private static final List<String> LOGIN_RESULTS = List.of("成功", "失败");
    private static final List<String> LOGIN_FAIL_REASON = List.of("密码错误", "被封禁用户尝试登录", "已注销用户尝试登录");

    private final PasswordEncoder passwordEncoder;
    private final AesUtil aesUtil;
    private final TenantService tenantService;
    private final UserTenantService userTenantService;
    private final NotificationService notificationService;
    private final LoginDeviceService loginDeviceService;
    private final JwtUtil jwtUtil;
    private final StringRedisTemplate stringRedisTemplate;
    private final RBloomFilter<String> usernameBloomFilter;
    private final RedissonClient redissonClient;
    private final GeoIpUtil geoIpUtil;
    private final UserPrivacyMapper userPrivacyMapper;

    @Lazy
    @Autowired
    private UserServiceImpl self;
    @Autowired
    private LoginHistoryService loginHistoryService;
    @Autowired
    private AvatarService avatarService;

    @Value("${verse.s3.base-url}")
    private String s3BaseUrl;

    @Value("${verse.s3.bucket}")
    private String bucket;

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
                    stringRedisTemplate.opsForSet().add(RedisKeyConstant.USER_PHONE_KEY + phoneHash,
                            userDO.getUserId().toString());
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
        // 注册完成的用户新建隐私设置
        UserPrivacyDO userPrivacyDO = UserPrivacyDO.builder()
                .userId(userId)
                .showRegion(1)
                .showTimezone(1)
                .showBio(1)
                .build();
        int insert = userPrivacyMapper.insert(userPrivacyDO);
        if (insert < 1) {
            // 不阻塞
            log.error("register user {} created privacy error", userId);
        }

        // 给注册完的用户推送一条系统通知
        try {
            notificationService.createAndPush(tenantId, "SYSTEM", "INFO",
                    WELCOME_MESSAGE_TITLE, String.format(WELCOME_MESSAGE_CONTENT, userDO.getNickname()), null, List.of(userDO.getUserId()));
        } catch (Exception e) {
            log.error("Create and push notification for user register error: user {}", userDO.getUserId());
        }

        return userDO;
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam, HttpServletRequest request) {
        // 提前解析设备信息
        String userAgent = request.getHeader("User-Agent");
        String ip = DeviceUtil.getClientIp(request);
        String deviceName = DeviceUtil.parseDeviceName(userAgent);
        String region = geoIpUtil.lookupRegion(ip);
        String deviceId = DeviceUtil.generateDeviceId(userAgent, ip);

        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername());
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            String hashedInput = aesUtil.hashForLookup(requestParam.getUsername());
            LambdaQueryWrapper<UserDO> phoneQueryWrapper = Wrappers.lambdaQuery(UserDO.class)
                    .eq(UserDO::getPhoneHash, hashedInput);
            UserDO phoneQueryUserDO = baseMapper.selectOne(phoneQueryWrapper);
            if (phoneQueryUserDO == null) {
                throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
            }
            log.debug("User logged in via phone fallback, userId: {}", phoneQueryUserDO.getUserId());
            userDO = phoneQueryUserDO;
        }
        if (userDO.getStatus() == null || userDO.getStatus().equals(UserStatusEnum.USER_STATUS_DISABLED.getStatusCode())) {
            loginHistoryService.recordLoginHistory(userDO.getUserId(), deviceName, ip,
                    region, LOGIN_RESULTS.get(1), LOGIN_RESULTS.get(1));
            throw new ClientException(UserErrorCodeEnum.USER_ACCOUNT_BANNED);
        } else if (userDO.getStatus().equals(UserStatusEnum.USER_STATUS_CLOSED.getStatusCode())) {
            String cancelTime = userDO.getCancelTime() != null
                    ? CANCEL_TIME_FORMATTER.format(userDO.getCancelTime().toInstant().atZone(ZoneId.systemDefault()))
                    : "较早前";
            String message = String.format(CLOSED_ACCOUNT_LOGIN_WARNING, cancelTime);
            loginHistoryService.recordLoginHistory(userDO.getUserId(), deviceName, ip,
                    region, LOGIN_RESULTS.get(1), LOGIN_FAIL_REASON.get(2));
            throw new ClientException(message, UserErrorCodeEnum.USER_ACCOUNT_CLOSED);
        }
        if (!passwordEncoder.matches(requestParam.getPassword(), userDO.getPassword())) {
            loginHistoryService.recordLoginHistory(userDO.getUserId(), deviceName, ip,
                    region, LOGIN_RESULTS.get(1), LOGIN_FAIL_REASON.getFirst());
            throw new ClientException(UserErrorCodeEnum.PASSWORD_ERROR);
        }

        // 生成 JWT Token
        String token = jwtUtil.generateToken(userDO.getUserId(), userDO.getUsername());
        Date expiresAt = new Date(System.currentTimeMillis() + 86400000);

        // 构建含设备字段的会话信息
        long ttl = expiresAt.getTime() - System.currentTimeMillis();
        LoginSessionVO session = LoginSessionVO.builder()
                .userId(userDO.getUserId())
                .username(userDO.getUsername())
                .token(token)
                .expiresAt(expiresAt)
                .lastActiveTenantId(userDO.getLastActiveTenantId())
                .loginTime(new Date())
                .deviceId(deviceId)
                .deviceName(deviceName)
                .ip(ip)
                .region(region)
                .build();

        // 写入 Redis 多设备 Hash
        String hashKey = RedisKeyConstant.USER_DEVICES_KEY + userDO.getUserId();
        stringRedisTemplate.opsForHash().put(hashKey, deviceId, JSON.toJSONString(session));
        stringRedisTemplate.expire(hashKey, ttl, TimeUnit.MILLISECONDS);

        // Token 反向索引
        String tokenHash = DigestUtil.md5Hex(token);
        stringRedisTemplate.opsForValue().set(
                RedisKeyConstant.USER_LOGIN_TOKEN_KEY + tokenHash,
                userDO.getUserId().toString(),
                ttl, TimeUnit.MILLISECONDS);

        // UPSERT 设备记录到 MySQL
        loginDeviceService.upsertLoginDevice(userDO.getUserId(), deviceId, deviceName, ip, region);
        loginHistoryService.recordLoginHistory(userDO.getUserId(), deviceName, ip, region, LOGIN_RESULTS.getFirst(), null);

        UserLoginRespDTO resp = new UserLoginRespDTO();
        BeanUtil.copyProperties(userDO, resp);
        resp.setToken(token);
        resp.setExpiresAt(expiresAt);

        if (userDO.getLastActiveTenantId() != null) {
            try {
                TenantInfoRespDTO tenantInfoResp = tenantService.getTenantInfo(userDO.getUserId(), userDO.getLastActiveTenantId());
                if (tenantInfoResp != null) {
                    String role = userTenantService.getRoleByUserIdAndTenantId(userDO.getUserId(), tenantInfoResp.getTenantId());
                    resp.setCurrentTenant(new UserLoginRespDTO.TenantInfo()
                            .setTenantId(tenantInfoResp.getTenantId())
                            .setName(tenantInfoResp.getName())
                            .setType(tenantInfoResp.getType())
                            .setRole(role));
                }
            } catch (ClientException e) {
                log.warn("上次活跃租户不可用, userId: {}, lastActiveTenantId: {}, 尝试回退到个人租户",
                        userDO.getUserId(), userDO.getLastActiveTenantId());
                Long personalTenantId = tenantService.getPersonalTenantId(userDO.getUserId());
                if (personalTenantId != null) {
                    TenantInfoRespDTO tenantInfoResp = tenantService.getTenantInfo(userDO.getUserId(), personalTenantId);
                    String role = userTenantService.getRoleByUserIdAndTenantId(userDO.getUserId(), personalTenantId);
                    resp.setCurrentTenant(new UserLoginRespDTO.TenantInfo()
                            .setTenantId(tenantInfoResp.getTenantId())
                            .setName(tenantInfoResp.getName())
                            .setType(tenantInfoResp.getType())
                            .setRole(role));

                    // 修正 last_active_tenant_id，避免后续登录重复触发容错
                    LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                            .eq(UserDO::getUserId, userDO.getUserId())
                            .eq(UserDO::getDelFlag, 0)
                            .set(UserDO::getLastActiveTenantId, personalTenantId);
                    baseMapper.update(null, updateWrapper);

                    // 更新 Redis Hash 中的 lastActiveTenantId
                    session.setLastActiveTenantId(personalTenantId);
                    stringRedisTemplate.opsForHash().put(hashKey, deviceId, JSON.toJSONString(session));
                }
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
        // 查询隐私设置
        UserPrivacyDO privacy = userPrivacyMapper.selectOne(Wrappers.lambdaQuery(UserPrivacyDO.class)
                .eq(UserPrivacyDO::getUserId, userId));

        UserDO userDO = queryActiveUserFromUserId(userId);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        // 解密敏感字段
        userDO.setEmail(aesUtil.decrypt(userDO.getEmail()));
        userDO.setPhone(aesUtil.decrypt(userDO.getPhone()));

        UserRespDTO result = new UserRespDTO();
        BeanUtil.copyProperties(userDO, result);
        result.setAvatar(userDO.getAvatar() != null ? s3BaseUrl + "/" + bucket + "/" + userDO.getAvatar() : null);
        result.setBio(userDO.getBio());
        result.setRegion(userDO.getRegion());
        result.setTimezone(userDO.getTimezone());
        result.setPrivacy(UserPrivacyRespDTO.builder()
                .showBio(privacy != null && privacy.getShowBio() == 1)
                .showRegion(privacy != null && privacy.getShowRegion() == 1)
                .showTimezone(privacy != null && privacy.getShowTimezone() == 1)
                .build());
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(result), 1, TimeUnit.HOURS);

        if (mask) {
            result.setPhone(SensitiveUtil.maskPhone(result.getPhone()));
            result.setEmail(SensitiveUtil.maskEmail(result.getEmail()));
        }
        return result;
    }

    @Override
    public UserInfoRespDTO getUserInfo(Long userId) {
        String cacheKey = RedisKeyConstant.USER_PUBLIC_PROFILE_KEY + userId;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            return JSON.parseObject(cachedJson, UserInfoRespDTO.class);
        }

        UserDO userDO = queryActiveUserFromUserId(userId);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        UserPrivacyDO privacy = userPrivacyMapper.selectOne(Wrappers.lambdaQuery(UserPrivacyDO.class)
                .eq(UserPrivacyDO::getUserId, userId));

        UserInfoRespDTO result = new UserInfoRespDTO();
        BeanUtil.copyProperties(userDO, result);
        result.setAvatar(userDO.getAvatar() != null ? s3BaseUrl + "/" + bucket + "/" + userDO.getAvatar() : null);
        if (privacy == null || privacy.getShowBio() == 0) {
            result.setBio(null);
        }
        if (privacy == null || privacy.getShowRegion() == 0) {
            result.setRegion(null);
        }
        if (privacy == null || privacy.getShowTimezone() == 0) {
            result.setTimezone(null);
        }
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(result), 1, TimeUnit.HOURS);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateProfile(Long userId, UserUpdateReqDTO requestParam) {
        // 查询当前用户记录，用于判断邮箱/手机号是否变更
        UserDO currentUser = queryActiveUserFromUserId(userId);
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

        // 处理简介
        if (StrUtil.isNotBlank(requestParam.getBio())) {
            updateWrapper.set(UserDO::getBio, requestParam.getBio());
        } else if (requestParam.getBio() != null) {
            updateWrapper.set(UserDO::getBio, null);
        }
        // 处理地区
        if (StrUtil.isNotBlank(requestParam.getRegion())) {
            updateWrapper.set(UserDO::getRegion, requestParam.getRegion());
        } else if (requestParam.getRegion() != null) {
            updateWrapper.set(UserDO::getRegion, null);
        }
        // 处理时区
        if (StrUtil.isNotBlank(requestParam.getTimezone())) {
            updateWrapper.set(UserDO::getTimezone, requestParam.getTimezone());
        } else if (requestParam.getTimezone() != null) {
            updateWrapper.set(UserDO::getTimezone, null);
        }

        int update = baseMapper.update(null, updateWrapper);
        if (update < 0) {
            log.error("Failed to update user profile for userId: {}, requestParam: {}", userId, requestParam);
            throw new ServerException(UserErrorCodeEnum.USER_UPDATE_ERROR);
        }

        // DB 更新成功后，删除用户信息缓存
        stringRedisTemplate.delete(RedisKeyConstant.USER_PROFILE_KEY + userId);
        stringRedisTemplate.delete(RedisKeyConstant.USER_PUBLIC_PROFILE_KEY + userId);

        // 邮箱变更时，维护 Redis Set：旧邮箱移除绑定，新邮箱添加绑定
        if (emailChanged) {
            String newEmailHash = aesUtil.hashForLookup(newEmail);
            stringRedisTemplate.opsForSet().remove(RedisKeyConstant.USER_EMAIL_COUNT_KEY + oldEmailHash,
                    userId.toString());
            stringRedisTemplate.opsForSet().add(RedisKeyConstant.USER_EMAIL_COUNT_KEY + newEmailHash,
                    userId.toString());
        }
        // 手机号变更时，维护 Redis Set：旧手机号移除，新手机号添加
        if (phoneChanged) {
            String newPhoneHash = aesUtil.hashForLookup(newPhone);
            String oldPhoneHash = aesUtil.hashForLookup(oldPhone);
            stringRedisTemplate.opsForSet().remove(RedisKeyConstant.USER_PHONE_KEY + oldPhoneHash,
                    userId.toString());
            stringRedisTemplate.opsForSet().add(RedisKeyConstant.USER_PHONE_KEY + newPhoneHash,
                    userId.toString());
        }

        return true;
    }

    @Override
    public Boolean logout(Long userId, HttpServletRequest request) {
        String currentDeviceId = DeviceUtil.generateDeviceId(
                request.getHeader("User-Agent"), DeviceUtil.getClientIp(request));

        // 从 Redis Hash 中取出当前设备的会话
        String hashKey = RedisKeyConstant.USER_DEVICES_KEY + userId;
        Object sessionJson = stringRedisTemplate.opsForHash().get(hashKey, currentDeviceId);
        if (sessionJson != null) {
            LoginSessionVO session = JSON.parseObject(sessionJson.toString(), LoginSessionVO.class);
            // 删除 Token 反向索引
            String tokenHash = DigestUtil.md5Hex(session.getToken());
            stringRedisTemplate.delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + tokenHash);
        }
        // 从 Redis Hash 中删除当前设备
        stringRedisTemplate.opsForHash().delete(hashKey, currentDeviceId);

        // 更新 MySQL 设备状态为离线
        loginDeviceService.logoutDevice(userId, currentDeviceId);

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePassword(Long userId, UserUpdatePasswordReqDTO requestParam) {
        UserDO userDO = queryActiveUserFromUserId(userId);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        if (!passwordEncoder.matches(requestParam.getOldPassword(), userDO.getPassword())) {
            throw new ClientException(UserErrorCodeEnum.PASSWORD_ERROR_FOR_RESET);
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

    @Override
    public PrepareCloseAccountRespDTO prepareCloseAccount(Long userId) {
        UserDO userDO = queryActiveUserFromUserId(userId);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        PrepareCloseAccountRespDTO resp = new PrepareCloseAccountRespDTO();
        resp.setWarningDescription(CLOSE_ACCOUNT_WARNING_DESCRIPTION);
        resp.setWarningTips(CLOSE_ACCOUNT_WARNING_TIPS);
        return resp;
    }

    @Override
    public Boolean closeAccountSendCode(Long userId) {
        UserDO userDO = queryActiveUserFromUserId(userId);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }
        // 检查是否发送过于频繁（60 秒间隔，按用户 ID 区分）
        String rateKey = RedisKeyConstant.USER_CLOSE_ACCOUNT_SENDING_CODE_KEY + "rate:" + userId;
        Boolean isAbsent = stringRedisTemplate.opsForValue().setIfAbsent(rateKey, "1", 60, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(isAbsent)) {
            throw new ClientException(UserErrorCodeEnum.USER_PHONE_CODE_SEND_FREQUENT);
        }

        // 生成验证码并保存到 Redis，5 分钟有效
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
        String codeKey = RedisKeyConstant.USER_CLOSE_ACCOUNT_SENDING_CODE_KEY + userId;
        stringRedisTemplate.opsForValue().set(codeKey, code, 5, TimeUnit.MINUTES);
        // 返回true
        return Boolean.TRUE;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean confirmCloseAccount(Long userId, ConfirmCloseAccountReqDTO requestParam) {
        UserDO userDO = queryActiveUserFromUserId(userId);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
        }

        // 验证验证码是否正确
        String codeKey = RedisKeyConstant.USER_CLOSE_ACCOUNT_SENDING_CODE_KEY + userId;
        String storedCode = stringRedisTemplate.opsForValue().get(codeKey);
        if (storedCode == null || !storedCode.equals(requestParam.getCode())) {
            throw new ClientException(UserErrorCodeEnum.USER_PHONE_CODE_ERROR);
        }
        stringRedisTemplate.delete(codeKey);

        // 注销账号，设置状态为2，del flag为1
        // phone_hash 和 email_hash 添加后缀以释放原始值供新用户使用（数据库有唯一约束）
        // 更新 cancelTime 为当前时间
        String closedSuffix = ":" + userId;
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUserId, userId)
                .set(UserDO::getStatus, UserStatusEnum.USER_STATUS_CLOSED.getStatusCode())
                .set(UserDO::getPhoneHash, userDO.getPhoneHash() + closedSuffix)
                .set(UserDO::getEmailHash, userDO.getEmailHash() + closedSuffix)
                .set(UserDO::getCancelTime, new Date())
                .set(UserDO::getDelFlag, 1);
        int update = baseMapper.update(updateWrapper);
        if (update < 1) {
            log.error("Failed to close account for userId: {}", userId);
            throw new ServerException(UserErrorCodeEnum.USER_CLOSE_ACCOUNT_ERROR);
        }

        // 删除用户缓存
        stringRedisTemplate.delete(RedisKeyConstant.USER_PROFILE_KEY + userId);
        stringRedisTemplate.delete(RedisKeyConstant.USER_PUBLIC_PROFILE_KEY + userId);
        // 释放手机号绑定
        String phoneHash = userDO.getPhoneHash();
        if (phoneHash != null) {
            stringRedisTemplate.opsForSet().remove(RedisKeyConstant.USER_PHONE_KEY + phoneHash,
                    userId.toString());
        }
        // 释放邮箱绑定
        String emailHash = userDO.getEmailHash();
        if (emailHash != null) {
            stringRedisTemplate.opsForSet().remove(RedisKeyConstant.USER_EMAIL_COUNT_KEY + emailHash,
                    userId.toString());
        }
        // 删除用户所有设备登录会话
        logoutAllDevices(userId);

        return Boolean.TRUE;
    }

    @Override
    public String uploadAvatar(Long userId, MultipartFile file) {
        String avatarUrl = avatarService.uploadAvatar(userId, file);
        stringRedisTemplate.delete(RedisKeyConstant.USER_PROFILE_KEY + userId);
        stringRedisTemplate.delete(RedisKeyConstant.USER_PUBLIC_PROFILE_KEY + userId);
        return avatarUrl;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePrivacy(Long userId, UserPrivacyUpdateReqDTO requestParam) {
        LambdaUpdateWrapper<UserPrivacyDO> updateWrapper = Wrappers.lambdaUpdate(UserPrivacyDO.class)
                .eq(UserPrivacyDO::getUserId, userId)
                .set(UserPrivacyDO::getShowBio, requestParam.getShowBio() ? 1 : 0)
                .set(UserPrivacyDO::getShowTimezone, requestParam.getShowTimezone() ? 1 : 0)
                .set(UserPrivacyDO::getShowRegion, requestParam.getShowRegion() ? 1 : 0);
        int update = userPrivacyMapper.update(updateWrapper);
        if (update < 0) {
            throw new ServerException(UserErrorCodeEnum.USER_PRIVACY_UPDATE_ERROR);
        } else if (update == 0) {
            UserPrivacyDO userPrivacyDO = UserPrivacyDO.builder()
                    .userId(userId)
                    .showBio(requestParam.getShowBio() ? 1 : 0)
                    .showTimezone(requestParam.getShowTimezone() ? 1 : 0)
                    .showRegion(requestParam.getShowRegion() ? 1 : 0)
                    .createTime(new Date())
                    .build();
            userPrivacyMapper.insert(userPrivacyDO);
        }
        // DB 更新成功后，删除用户信息缓存
        stringRedisTemplate.delete(RedisKeyConstant.USER_PROFILE_KEY + userId);
        stringRedisTemplate.delete(RedisKeyConstant.USER_PUBLIC_PROFILE_KEY + userId);
        return Boolean.TRUE;
    }

    private Long getEmailBindCount(String email) {
        // 用Redis的Set维护一个邮箱绑定的用户ID集合（Key使用邮箱哈希），直接查询集合的大小即可
        String emailHash = aesUtil.hashForLookup(email);
        Long userIdCount = stringRedisTemplate.opsForSet().size(RedisKeyConstant.USER_EMAIL_COUNT_KEY + emailHash);
        return Objects.requireNonNullElse(userIdCount, 0L);
    }

    private Boolean hasPhone(String phone) {
        String phoneHash = aesUtil.hashForLookup(phone);
        Long size = stringRedisTemplate.opsForSet().size(RedisKeyConstant.USER_PHONE_KEY + phoneHash);
        return size != null && size > 0;
    }

    private UserDO queryActiveUserFromUserId(Long userId) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId)
                .eq(UserDO::getDelFlag, 0)
                .eq(UserDO::getStatus, UserStatusEnum.USER_STATUS_ACTIVE.getStatusCode());
        return baseMapper.selectOne(queryWrapper);
    }

    private void logoutAllDevices(Long userId) {
        String hashKey = RedisKeyConstant.USER_DEVICES_KEY + userId;
        Map<Object, Object> sessions = stringRedisTemplate.opsForHash().entries(hashKey);
        for (Object sessionJson : sessions.values()) {
            LoginSessionVO session = JSON.parseObject(sessionJson.toString(), LoginSessionVO.class);
            String tokenHash = DigestUtil.md5Hex(session.getToken());
            stringRedisTemplate.delete(RedisKeyConstant.USER_LOGIN_TOKEN_KEY + tokenHash);
        }
        stringRedisTemplate.delete(hashKey);
        loginDeviceService.logoutAllDevice(userId);
    }
}