package com.yonagi.verse.common.enums;

/**
 * 权限枚举 — 定义系统内所有功能权限原子
 *
 * <p>权限字符串格式: {@code domain:resource:action}，对应 Spring Security 的
 * {@code hasAuthority('tenant:member:invite')} SpEL 表达式。</p>
 *
 * <p>维护方式：
 * <ol>
 *   <li>新增权限 → 在本枚举中添加一行</li>
 *   <li>调整角色权限 → 修改 {@link RoleEnum#getPermissions()} 中的映射</li>
 *   <li>废弃权限 → 移除枚举值，编译器会自动指出所有引用该值的位置</li>
 * </ol>
 *
 * @author Yonagi
 */
public enum PermissionEnum {

    // === 用户自身操作 ===
    USER_PROFILE_READ("user:profile:read", "查看个人信息"),
    USER_PROFILE_WRITE("user:profile:write", "修改个人信息"),
    USER_PASSWORD_WRITE("user:password:write", "修改密码"),

    // === 租户管理 ===
    TENANT_CREATE("tenant:create", "创建租户"),
    TENANT_DELETE("tenant:delete", "删除租户"),
    TENANT_SETTINGS_WRITE("tenant:settings:write", "修改租户设置"),
    TENANT_READ("tenant:read", "查看租户信息"),

    // === 租户成员管理 ===
    TENANT_MEMBER_INVITE("tenant:member:invite", "邀请成员"),
    TENANT_MEMBER_REMOVE("tenant:member:remove", "移除成员"),
    TENANT_MEMBER_ROLE_WRITE("tenant:member:role:write", "修改成员角色"),
    TENANT_MEMBER_READ("tenant:member:read", "查看成员列表"),

    // === LLM 服务管理 ===
    TENANT_LLM_REGISTER("tenant:llm:register", "注册 LLM 服务"),
    TENANT_LLM_UPDATE("tenant:llm:update", "修改 LLM 服务"),
    TENANT_LLM_DELETE("tenant:llm:delete", "删除 LLM 服务"),
    TENANT_LLM_READ("tenant:llm:read", "查看 LLM 服务列表"),
    TENANT_LLM_CREDENTIAL_READ("tenant:llm:credential:read", "查看 LLM 服务 API Key"),

    // === Token 消耗 ===
    TENANT_TOKENS_READ_OWN("tenant:tokens:read:own", "查看自己的 Token 消耗"),
    TENANT_TOKENS_READ_ALL("tenant:tokens:read:all", "查看全员 Token 消耗"),

    // === 调用日志 ===
    TENANT_LOGS_READ_OWN("tenant:logs:read:own", "查看自己的调用日志"),
    TENANT_LOGS_READ_ALL("tenant:logs:read:all", "查看全员调用日志"),

    // === 限流 ===
    TENANT_RATELIMIT_WRITE("tenant:ratelimit:write", "设置限流策略"),

    // === API Key ===
    API_KEY_CREATE("api_key:create", "创建 API Key"),
    API_KEY_DELETE("api_key:delete", "删除 API Key"),
    API_KEY_READ("api_key:read", "查看 API Key 列表"),
    ;

    private final String code;
    private final String description;

    PermissionEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }
}
