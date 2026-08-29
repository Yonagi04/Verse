package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/**
 * @author Yonagi
 * @version 1.0
 * @program Verse
 * @description
 * @date 2026/08/22 11:25
 */
public enum LlmManageErrorCodeEnum implements IErrorCode {

    LLM_SERVICE_ID_IS_NULL("A000700", "模型服务ID不能为空"),
    LLM_SERVICE_IS_NOT_EXIST("A000701", "模型不存在"),
    LLM_NAME_DUPLICATED("A000702", "模型别名已存在，请更换后重试"),
    LLM_CAN_NOT_UPDATE("A000703", "该模型已停用，不支持更新"),
    LLM_HAS_BEEN_DISABLED("A000704", "该模型已停用"),
    LLM_HAS_BEEN_ENABLED("A000705", "该模型已启用"),
    LLM_REMOVE_TOKEN_EXPIRED("A000706", "Token已过期"),
    PAGINATION_PARAM_INVALID("A000707", "分页参数不合法"),
    LLM_UPDATE_PARAM_EMPTY("A000708", "至少填写一个需要更新的字段"),
    LLM_FALLBACK_ID_INVALID("A000709", "模型的备用模型不能映射到自身"),
    LLM_FALLBACK_INVALID("A000710", "备用模型不存在或不可用"),

    LLM_ADD_FAILED("B000700", "添加模型失败"),
    LLM_UPDATE_FAILED("B000701", "更新模型失败"),
    LLM_DISABLE_FAILED("B000702", "停用模型失败"),
    LLM_ENABLE_FAILED("B000703", "启用模型失败"),
    LLM_REMOVE_FAILED("B000704", "删除模型失败"),
    THREAD_INTERRUPTED("B000705", "线程中断异常"),
    ;

    private final String code;
    private final String message;

    LlmManageErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
