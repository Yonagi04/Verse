package com.yonagi.verse.common.convention.errorcode;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/06/18 14:56
 */
public interface IErrorCode {

    /**
     * @return 错误码
     */
    String code();

    /**
     * @return 错误信息
     */
    String message();
}
