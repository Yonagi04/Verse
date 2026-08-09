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
        if (pageSize == null) {
            pageSize = 10;
        }
        Page<LoginHistoryDO> pages;
        String cacheKey = RedisKeyConstant.USER_LOGIN_HISTORY_KEY + userId;
        String cachedJson = stringRedisTemplate.opsForValue().get(cacheKey);
        boolean isCached = false;

        if (cachedJson != null) {
            pages = JSON.parseObject(cachedJson, Page.class);
            isCached = true;
        } else {
            UserDO userDO = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                    .eq(UserDO::getUserId, userId));
            Date createTime = userDO.getCreateTime();
            pages = baseMapper.selectPage(new Page<>(pageNum, pageSize), Wrappers.lambdaQuery(LoginHistoryDO.class)
                    .eq(LoginHistoryDO::getUserId, userId)
                    .ge(LoginHistoryDO::getLoginTime, createTime));
        }
        List<LoginHistoryDO> records = pages.getRecords();
        List<LoginHistoryRespDTO.LoginHistoryInfo> historyInfos = new ArrayList<>();
        for (LoginHistoryDO loginHistoryDO : records) {
            historyInfos.add(BeanUtil.copyProperties(loginHistoryDO, LoginHistoryRespDTO.LoginHistoryInfo.class));
        }
        if (!isCached) {
            stringRedisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(pages));
        }

        LoginHistoryRespDTO respDTO = new LoginHistoryRespDTO();
        respDTO.setTotal(pages.getTotal());
        respDTO.setTotalPages(pages.getPages());
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
        stringRedisTemplate.delete(RedisKeyConstant.USER_LOGIN_HISTORY_KEY + userId);
    }
}
