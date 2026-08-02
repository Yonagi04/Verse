package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yonagi.verse.dao.entity.TenantJoinRequestDO;
import com.yonagi.verse.dao.mapper.TenantJoinRequestMapper;
import com.yonagi.verse.service.TenantJoinRequestService;
import org.springframework.stereotype.Service;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/02 19:16
 */
@Service
public class TenantJoinRequestServiceImpl extends ServiceImpl<TenantJoinRequestMapper, TenantJoinRequestDO>
        implements TenantJoinRequestService {

}
