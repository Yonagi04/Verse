package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
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
import com.yonagi.verse.dto.req.UserLoginReqDTO;
import com.yonagi.verse.dto.req.UserRegisterReqDTO;
import com.yonagi.verse.dto.req.UserUpdatePasswordReqDTO;
import com.yonagi.verse.dto.req.UserUpdateReqDTO;
import com.yonagi.verse.dto.resp.LoginSessionVO;
import com.yonagi.verse.dto.resp.UserLoginRespDTO;
import com.yonagi.verse.dto.resp.UserRegisterRespDTO;
import com.yonagi.verse.dto.resp.UserRespDTO;
import com.yonagi.verse.service.UserService;
import lombok.RequiredArgsConstructor;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
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

    @Override
    public Boolean hasUsername(String username) {
        // TODO 接入redis后用布隆过滤器查username
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, username);
        return baseMapper.selectOne(queryWrapper) != null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserRegisterRespDTO register(UserRegisterReqDTO requestParam) {
        if (hasUsername(requestParam.getUsername())) {
            throw new ClientException(UserErrorCodeEnum.USERNAME_EXIST);
        }
        long emailBindCount = getEmailBindCount(requestParam.getEmail());
        if (emailBindCount >= 3) {
            throw new ClientException(UserErrorCodeEnum.EMAIL_BIND_COUNT_EXCEED);
        }

        Long userId = SnowflakeIdUtil.nextId();
        String encodedPassword = passwordEncoder.encode(requestParam.getPassword());

        UserDO userDO = new UserDO();
        BeanUtil.copyProperties(requestParam, userDO);
        userDO.setUserId(userId);
        userDO.setPassword(encodedPassword);
        userDO.setStatus(1);
        userDO.setDelFlag(0);

        int inserted = baseMapper.insert(userDO);
        if (inserted < 1) {
            throw new ClientException(UserErrorCodeEnum.USER_SAVED_ERROR);
        }

        Long tenantId = SnowflakeIdUtil.nextId();
        TenantDO tenantDO = new TenantDO();
        tenantDO.setTenantId(tenantId);
        tenantDO.setName(requestParam.getUsername() + "的个人空间");
        tenantDO.setType("PERSONAL");
        tenantDO.setOwnerId(userId);
        tenantDO.setStatus(1);
        tenantDO.setDelFlag(0);
        tenantMapper.insert(tenantDO);

        // 建立用户-租户关联（SUPER_ADMIN 角色）
        UserTenantDO userTenantDO = new UserTenantDO();
        userTenantDO.setUserId(userId);
        userTenantDO.setTenantId(tenantId);
        userTenantDO.setRole(RoleEnum.SUPER_ADMIN.name());
        userTenantDO.setJoinedAt(new Date());
        userTenantMapper.insert(userTenantDO);

        // 设置用户的活跃租户
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUserId, userId)
                .set(UserDO::getLastActiveTenantId, tenantId);
        baseMapper.update(null, updateWrapper);

        UserRegisterRespDTO resp = new UserRegisterRespDTO();
        resp.setUserId(userId);
        resp.setUsername(requestParam.getUsername());
        return resp;
    }

    @Override
    public UserLoginRespDTO login(UserLoginReqDTO requestParam) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUsername, requestParam.getUsername());
        UserDO userDO = baseMapper.selectOne(queryWrapper);
        if (userDO == null) {
            throw new ClientException(UserErrorCodeEnum.USER_NOT_EXIST);
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
        if (hasUsername(requestParam.getUsername())) {
            throw new ClientException(UserErrorCodeEnum.USERNAME_EXIST);
        }
        Long emailBindCount = getEmailBindCount(requestParam.getEmail());
        if (emailBindCount >= 3) {
            throw new ClientException(UserErrorCodeEnum.EMAIL_BIND_COUNT_EXCEED);
        }

        // 更新
        LambdaUpdateWrapper<UserDO> updateWrapper = Wrappers.lambdaUpdate(UserDO.class)
                .eq(UserDO::getUserId, userId);
        int update = baseMapper.update(BeanUtil.toBean(requestParam, UserDO.class), updateWrapper);
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

    private Long getEmailBindCount(String email) {
        LambdaQueryWrapper<UserDO> queryWrapper = Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getEmail, email);
        return baseMapper.selectCount(queryWrapper);
    }
}