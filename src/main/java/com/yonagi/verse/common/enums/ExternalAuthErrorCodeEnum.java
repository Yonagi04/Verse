package com.yonagi.verse.common.enums;
import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import lombok.RequiredArgsConstructor;
@RequiredArgsConstructor
public enum ExternalAuthErrorCodeEnum implements IErrorCode {
    FLOW_EXPIRED("A002100", "认证流程已过期，请重新认证"),
    FLOW_INVALID("A002101", "请在发起认证的浏览器标签页重新认证"),
    FLOW_STATE_CHANGED("A002102", "账号关联已变化，请刷新后重新确认"),
    EXTERNAL_QUOTA_EXCEEDED("A002103", "此外部账户最多关联3个Verse账号"),
    PROVIDER_ALREADY_BOUND("A002104", "当前账号已绑定该平台的其他账户，请先解绑"),
    REAUTH_REQUIRED("A002105", "请验证当前Verse账号密码后继续"),
    LAST_LOGIN_METHOD("A002106", "请先设置密码或绑定另一种可用登录方式"),
    VERSE_SESSION_CHANGED("A002107", "发起绑定的登录会话已变化，请重新发起"),
    AUTHORIZATION_CANCELLED("A002108", "已取消外部账户授权"),
    SESSION_REAUTH_REQUIRED("A002109", "登录结果无法恢复，请重新外部认证"),
    FLOW_IN_PROGRESS("A002110", "认证正在处理，请稍后查询"),
    PROVIDER_DISABLED("A002111", "该平台尚未启用或配置不完整"),
    ACTION_NOT_ALLOWED("A002112", "当前账号无法执行此操作"),
    FLOW_LIMIT_EXCEEDED("A002113", "同时进行的外部认证已达3个，请完成或取消其他标签页的认证后重试"),
    PROVIDER_UNAVAILABLE("C002100", "外部平台暂时不可用，请稍后重试"),
    PROVIDER_RESPONSE_INVALID("C002101", "外部平台身份验证失败，请重新认证");
    private final String code;
    private final String message;
    public String code() { return code; }
    public String message() { return message; }
}
