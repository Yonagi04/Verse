package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dto.req.ApiKeyCreateReqDTO;
import com.yonagi.verse.dto.resp.ApiKeyListRespDTO;
import com.yonagi.verse.dto.resp.ApiKeyRespDTO;

import java.util.List;

/**
 * API Key 管理服务
 *
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @date 2026/08/16
 */
public interface ApiKeyService extends IService<ApiKeyDO> {

    /**
     * 创建 API Key，仅本次返回完整 Key
     */
    ApiKeyRespDTO createApiKey(Long userId, Long tenantId, ApiKeyCreateReqDTO requestParam);

    /**
     * 查询当前用户在该租户下的 API Key 列表（不含完整 Key）
     */
    List<ApiKeyListRespDTO> listApiKeys(Long userId, Long tenantId);

    /**
     * 吊销 API Key（软删除 status=0）
     */
    Boolean revokeApiKey(Long userId, Long tenantId, Long keyId);
}
