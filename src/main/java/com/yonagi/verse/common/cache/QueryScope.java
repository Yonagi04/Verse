package com.yonagi.verse.common.cache;

import java.util.Objects;

/** 不可变的业务数据归属；范围只用于失效，不代表访问授权。 */
public final class QueryScope {
    /** 受控范围，广播只由缓存基础设施创建。 */
    public enum Kind {
        /** 兼容原全表读取。 */
        TABLE,
        /** 用户私有数据。 */
        USER,
        /** 租户共享数据。 */
        TENANT,
        /** 租户内用户私有数据。 */
        TENANT_USER,
        /** 未定位归属的全表写保护。 */
        BROADCAST
    }

    private static final QueryScope TABLE = new QueryScope(Kind.TABLE, null, null);
    private static final QueryScope BROADCAST = new QueryScope(Kind.BROADCAST, null, null);
    private final Kind kind;
    private final Long tenantId;
    private final Long userId;

    private QueryScope(Kind kind, Long tenantId, Long userId) {
        this.kind = kind;
        this.tenantId = tenantId;
        this.userId = userId;
    }

    /** @return 兼容原全表依赖的范围 */
    public static QueryScope table() {
        return TABLE;
    }

    /** @param userId 数据所有者，必须为正数 */
    public static QueryScope user(Long userId) {
        return new QueryScope(Kind.USER, null, positive(userId));
    }

    /** @param tenantId 数据所属租户，必须为正数 */
    public static QueryScope tenant(Long tenantId) {
        return new QueryScope(Kind.TENANT, positive(tenantId), null);
    }

    /** @return 租户与数据所有者的组合范围，两者均必须为正数 */
    public static QueryScope tenantUser(Long tenantId, Long userId) {
        return new QueryScope(Kind.TENANT_USER, positive(tenantId), positive(userId));
    }

    static QueryScope broadcast() {
        return BROADCAST;
    }

    /** @return 受控范围类型，不包含可变授权状态 */
    public Kind kind() {
        return kind;
    }

    /** @return 租户范围的标识；USER 和 TABLE 等范围为 null */
    public Long tenantId() {
        return tenantId;
    }

    /** @return 数据所有者标识；纯租户和全表范围为 null */
    public Long userId() {
        return userId;
    }

    String identity() {
        return switch (kind) {
            case TABLE -> "table";
            case BROADCAST -> "broadcast";
            case USER -> "user:" + userId;
            case TENANT -> "tenant:" + tenantId;
            case TENANT_USER -> "tenant:" + tenantId + ":user:" + userId;
        };
    }

    private static Long positive(Long value) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException("查询缓存范围 ID 必须为正数");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QueryScope scope && kind == scope.kind
                && Objects.equals(tenantId, scope.tenantId) && Objects.equals(userId, scope.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, tenantId, userId);
    }

    @Override
    public String toString() {
        return identity();
    }
}
