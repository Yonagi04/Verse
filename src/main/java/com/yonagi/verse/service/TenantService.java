package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.TenantDO;
import com.yonagi.verse.dto.req.TenantCreateReqDTO;
import com.yonagi.verse.dto.resp.TenantInfoListRespDTO;

import java.util.List;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/07/19 20:51
 */
public interface TenantService extends IService<TenantDO> {
    List<TenantInfoListRespDTO> listTenants(Long userId);

    Boolean createTenant(Long userId, TenantCreateReqDTO requestParam);

    Long createPersonalTenant(Long userId, String tenantName);
}
