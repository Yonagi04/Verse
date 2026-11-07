package com.yonagi.verse.dto.resp;

/** OpenAI 兼容错误信封。 */
public record OpenAiErrorRespDTO<T>(
        /** 客户端可读的错误详情。 */ T error) {

    /** 通用错误字段。 */
    public record DetailDTO(
            /** 可对客户端展示的错误说明。 */ String message,
            /** OpenAI 兼容错误类别。 */ String type,
            /** 客户端可识别的错误码。 */ String code) { }

    /** 按 Verse 网关错误码归类，供兼容接口共用。 */
    public static OpenAiErrorRespDTO<DetailDTO> gatewayError(String message, String code) {
        String type = "server_error";
        if (code != null && code.startsWith("A")) {
            type = "invalid_request_error";
        } else if (code != null && code.startsWith("C")) {
            type = "upstream_error";
        }
        return new OpenAiErrorRespDTO<>(new DetailDTO(message, type, code));
    }
}
