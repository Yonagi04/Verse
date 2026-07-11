package com.yonagi.verse.common.convention.result;

import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description 返回结果
 * @date 2026/05/18 19:15
 */
@Data
@Accessors(chain = true)
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 5679018624309023727L;

    /**
     * 正确响应码
     */
    public static final String SUCCESS_CODE = "0";

    /**
     * 响应码
     */
    private String code;

    /**
     * 响应message
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 请求ID
     */
    private String requestId;

    public boolean isSuccess() {
        return SUCCESS_CODE.equals(code);
    }
}
