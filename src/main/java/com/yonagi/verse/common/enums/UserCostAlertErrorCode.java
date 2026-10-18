package com.yonagi.verse.common.enums;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public enum UserCostAlertErrorCode implements IErrorCode {
    INVALID_SETTINGS("A001601", "个人预警配置无效，请检查金额和渠道"),
    VERSION_CONFLICT("A001602", "个人预警配置已变化，请加载最新配置"),
    CHANNEL_UNAVAILABLE("A001603", "所选个人通知渠道不可用，请检查联系方式及平台配置");
    private final String code;
    private final String message;
    public String code() { return code; }
    public String message() { return message; }
}
