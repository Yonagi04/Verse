package com.yonagi.verse.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.yonagi.verse.dao.entity.LlmServiceDO;
import com.yonagi.verse.dto.req.LlmServiceAddReqDTO;
import com.yonagi.verse.dto.req.LlmServiceRemoveReqDTO;
import com.yonagi.verse.dto.req.LlmServiceUpdateReqDTO;
import com.yonagi.verse.dto.resp.LlmServiceRemovePreRespDTO;
import com.yonagi.verse.dto.resp.LlmServiceInfoRespDTO;
import com.yonagi.verse.dto.resp.LlmServiceListRespDTO;
import jakarta.validation.Valid;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/22 11:07
 */
public interface LlmManageService extends IService<LlmServiceDO> {

    Boolean addLlmService(Long userId, Long tenantId, @Valid LlmServiceAddReqDTO requestParam);

    LlmServiceListRespDTO listLlmService(Long userId, Long tenantId, Integer pageNum, Integer pageSize);

    Boolean updateLlmService(Long userId, Long tenantId, Long serviceId, LlmServiceUpdateReqDTO requestParam);

    LlmServiceInfoRespDTO getLlmInfo(Long userId, Long tenantId, Long serviceId);

    Boolean disableLlmService(Long userId, Long tenantId, Long serviceId);

    Boolean enableLlmService(Long userId, Long tenantId, Long serviceId);

    LlmServiceRemovePreRespDTO prepareRemoveLlmService(Long userId, Long tenantId, Long serviceId);

    Boolean removeLlmService(Long userId, Long tenantId, Long serviceId, LlmServiceRemoveReqDTO requestParam);
}
