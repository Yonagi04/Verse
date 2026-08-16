package com.yonagi.verse.service;

import com.yonagi.verse.dto.resp.LoginHistoryRespDTO;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/09 18:37
 */
public interface LoginHistoryService {

    LoginHistoryRespDTO getLoginHistoryList(Long userId, Integer pageNum, Integer pageSize);
}
