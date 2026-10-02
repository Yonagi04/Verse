package com.yonagi.verse.dto.resp;
import java.util.List;
/** 私有外部认证契约，所有ID以字符串传输。 */
public final class ExternalAuthRespDTO {
    private ExternalAuthRespDTO() { }
    public record ProviderInfo(
        /** 平台 */ String provider, /** 是否已配置启用 */ boolean enabled,
        /** 可用性 */ String availability) { }
    public record Start(
        /** 流程定位符 */ String flowId, /** 标签页证明 */ String flowToken,
        /** 官方授权地址 */ String authorizationUrl, /** 有效期 */ String expiresAt) { }
    public record Account(
        /** 用户ID */ String userId, /** 唯一用户名 */ String username,
        /** 昵称 */ String nickname, /** 本站头像 */ String avatar, /** 账号状态 */ String status) { }
    public record Summary(
        /** 外部显示名 */ String displayName, /** 外部用户名 */ String username,
        /** 脱敏邮箱 */ String maskedEmail) { }
    public record Defaults(
        /** 昵称建议 */ String nickname, /** 仅已验证的邮箱建议 */ String verifiedEmail) { }
    public record CompletionSummary(
        /** 注册是否完成 */ boolean registrationCompleted, /** 会话状态 */ String sessionStatus) { }
    public record Context(
        /** 定位符 */ String flowId, /** 用途 */ String purpose, /** 阶段 */ String stage,
        /** 平台 */ String provider, /** 外部账户摘要 */ Summary externalAccount,
        /** 登录候选 */ List<Account> accounts, /** 注册建议 */ Defaults registrationDefaults,
        /** 固定绑定目标 */ Account targetAccount, /** 已绑定数量 */ Integer boundAccountCount,
        /** 有效期 */ String expiresAt, /** 可公开原因 */ String errorReason,
        /** 完成摘要 */ CompletionSummary completion) { }
    public record LoginCompletion(
        /** 登录结果 */ String outcome, /** 注册是否已提交 */ boolean registrationCompleted,
        /** Verse会话 */ UserLoginRespDTO login, /** 下一步 */ String nextAction) { }
    public record Binding(
        /** 关系ID */ String bindingId, /** 显示名 */ String displayName,
        /** 外部用户名 */ String username, /** 脱敏邮箱 */ String maskedEmail, /** 绑定时间 */ String boundAt) { }
    public record BindingInfo(
        /** 平台 */ String provider, /** 部署启用 */ boolean enabled, /** 可用性 */ String availability,
        /** 当前关系 */ Binding binding, /** 可绑定提示 */ boolean canBind,
        /** 可解绑提示 */ boolean canUnbind, /** 不可用原因 */ String unavailableReason) { }
    public record Reauth(
        /** 近期验证证明 */ String reauthToken, /** 证明有效期 */ String expiresAt) { }
    public record BindingResult(
        /** 关系ID */ String bindingId, /** 幂等结果 */ String outcome, /** 已有会话保持在线 */ boolean sessionsRetained) { }
}
