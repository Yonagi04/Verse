package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;

/** Skill广场稳定错误契约，不混用租户错误码。 */
public enum SkillErrorCodeEnum implements IErrorCode {
    /** 请求字段或格式不符合公开契约。 */
    INVALID_INPUT("A001701", "请检查输入内容"),
    /** 不存在与无权访问使用同一结果，避免泄漏私有资源。 */
    NOT_ACCESSIBLE("A001702", "内容不存在或暂不可访问"),
    /** 并发修订或资源唯一性发生冲突。 */
    CONFLICT("A001703", "内容已变化，请刷新后重试"),
    /** 同一幂等键不允许提交不同载荷。 */
    IDEMPOTENCY_CONFLICT("A001704", "此请求标识已用于不同内容"),
    /** 压缩包结构、内容或格式未通过检查。 */
    PACKAGE_INVALID("A001705", "Skill ZIP未通过检查"),
    /** 上传或解析实际消耗超过安全上限。 */
    LIMIT_EXCEEDED("A001706", "上传或处理资源超过限制"),
    /** 尚未完成公开分发所需的作者声明。 */
    LICENSE_REQUIRED("A001707", "请确认许可证、分发权和依赖声明"),
    /** 选定版本不满足当前下载条件。 */
    VERSION_UNAVAILABLE("A001708", "该版本暂不可下载"),
    /** 超过当前主体的操作频率。 */
    RATE_LIMITED("A001709", "操作过于频繁，请稍后重试"),
    /** 存储等必要依赖不可用，不伪装为空结果或成功。 */
    UNAVAILABLE("B001701", "Skill服务暂不可用，请稍后重试");

    private final String code;
    private final String message;

    SkillErrorCodeEnum(String code, String message) {
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
