package com.yonagi.verse.common.security;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 用户上下文 — 存储当前请求的用户信息
 *
 * @author Yonagi
 */
@Data
@Accessors(chain = true)
public class UserContext {

    /**
     * 用户业务 ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String username;
}
