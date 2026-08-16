package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.ApiKeyDO;
import com.yonagi.verse.dto.req.ApiKeyCreateReqDTO;
import com.yonagi.verse.dto.resp.ApiKeyPageRespDTO;
import com.yonagi.verse.dto.resp.ApiKeyRespDTO;

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
     * 分页查询当前用户在该租户下的 API Key 列表（不含完整 Key）
     */
    ApiKeyPageRespDTO listApiKeys(Long userId, Long tenantId, Integer pageNum, Integer pageSize);

    /**
     * 吊销 API Key（软删除 status=0）
     */
    Boolean revokeApiKey(Long userId, Long tenantId, Long apiKeyId);
}
