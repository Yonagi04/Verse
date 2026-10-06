package com.yonagi.verse.service.impl;

import com.yonagi.verse.common.cache.NoQueryAccess;

import cn.hutool.core.bean.BeanUtil;
import com.yonagi.verse.common.cache.QueryCached;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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

import static com.yonagi.verse.common.cache.QueryCacheTtl.*;
import static com.yonagi.verse.common.constant.RedisKeyConstant.*;

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
    @QueryCached(keyPrefix = USER_LOGIN_HISTORY_KEY, seconds = HOURS_1, access = NoQueryAccess.class,
            tables = {"t_user", "t_login_history"})
    public LoginHistoryRespDTO getLoginHistoryList(Long userId, Integer pageNum, Integer pageSize) {
        if (pageNum == null) {
            pageNum = 1;
        }
        if (pageSize == null) {
            pageSize = 10;
        }
        UserDO userDO = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class)
                .eq(UserDO::getUserId, userId));
        Date createTime = userDO.getCreateTime();
        Page<LoginHistoryDO> page = baseMapper.selectPage(new Page<>(pageNum, pageSize), Wrappers.lambdaQuery(LoginHistoryDO.class)
                .eq(LoginHistoryDO::getUserId, userId)
                .ge(LoginHistoryDO::getLoginTime, createTime)
                .orderByDesc(LoginHistoryDO::getLoginTime));

        LoginHistoryRespDTO respDTO = buildRespDTO(page, pageNum, pageSize);
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
}
