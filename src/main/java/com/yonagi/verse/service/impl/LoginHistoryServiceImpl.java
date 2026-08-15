package com.yonagi.verse.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.common.constant.RedisKeyConstant;
import com.yonagi.verse.dao.entity.LoginHistoryDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.LoginHistoryMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.resp.LoginHistoryRespDTO;
import com.yonagi.verse.service.LoginHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/09 18:38
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LoginHistoryServiceImpl extends ServiceImpl<LoginHistoryMapper, LoginHistoryDO> implements LoginHistoryService {

    private final StringRedisTemplate stringRedisTemplate;
    private final UserMapper userMapper;

    @Override
    public LoginHistoryRespDTO getLoginHistoryList(Long userId, Integer pageNum, Integer pageSize) {
        if (pageNum == null) {
            pageNum = 1;
        }
        if (pageSize == null) {
            pageSize = 10;
        }
        String cacheKey = RedisKeyConstant.USER_LOGIN_HISTORY_KEY + userId + ":" + pageNum + ":" + pageSize;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            return JSON.parseObject(cachedJson, LoginHistoryRespDTO.class);
        }

        UserDO userDO = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId));
        Date createTime = userDO.getCreateTime();
        Page<LoginHistoryDO> page = baseMapper.selectPage(new Page<>(pageNum, pageSize), Wrappers.lambdaQuery(LoginHistoryDO.class)
                .eq(LoginHistoryDO::getUserId, userId)
                .ge(LoginHistoryDO::getLoginTime, createTime));

        LoginHistoryRespDTO respDTO = buildRespDTO(page, pageNum, pageSize);
        stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(respDTO), 30, TimeUnit.MINUTES);
        return respDTO;
    }

    private LoginHistoryRespDTO buildRespDTO(Page<LoginHistoryDO> page, Integer pageNum, Integer pageSize) {
        List<LoginHistoryDO> records = page.getRecords();
        List<LoginHistoryRespDTO.LoginHistoryInfo> historyInfos = new ArrayList<>();
        for (LoginHistoryDO loginHistoryDO : records) {
            historyInfos.add(BeanUtil.copyProperties(loginHistoryDO, LoginHistoryRespDTO.LoginHistoryInfo.class));
        }

        LoginHistoryRespDTO respDTO = new LoginHistoryRespDTO();
        respDTO.setTotal(page.getTotal());
        respDTO.setTotalPages(page.getPages());
        respDTO.setPage(pageNum);
        respDTO.setPageSize(pageSize);
        respDTO.setHistoryInfos(historyInfos);
        return respDTO;
    }

    @Override
    public void recordLoginHistory(Long userId, String deviceName, String ip, String region, String result, String failReason) {
        LoginHistoryDO history = LoginHistoryDO.builder()
                .userId(userId)
                .deviceName(deviceName)
                .ip(ip)
                .region(region)
                .result(result)
                .failReason(failReason)
                .loginTime(new Date())
                .build();
        baseMapper.insert(history);
        invalidateLoginHistoryCache(userId);
    }

    private void invalidateLoginHistoryCache(Long userId) {
        Set<String> keys = stringRedisTemplate.keys(RedisKeyConstant.USER_LOGIN_HISTORY_KEY + userId + ":*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }
}
