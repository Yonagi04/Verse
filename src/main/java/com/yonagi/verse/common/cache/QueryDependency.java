package com.yonagi.verse.common.cache;

import java.util.Objects;
import java.util.regex.Pattern;

/** 表与业务范围的完整身份，所有缓存控制键共同使用此身份。 */
public record QueryDependency(
        /** 完整源表名。 */ String table,
        /** 数据归属范围。 */ QueryScope scope) {
    private static final Pattern TABLE_NAME = Pattern.compile("t_[a-z0-9_]+");

    public QueryDependency {
        if (!validTable(table)) {
            throw new IllegalArgumentException("查询缓存依赖表名无效");
        }
        Objects.requireNonNull(scope, "查询缓存依赖范围不能为空");
    }

    /** @return 原全表依赖，沿用旧 Redis 控制键 */
    public static QueryDependency table(String table) {
        return new QueryDependency(table, QueryScope.table());
    }

    static QueryDependency broadcast(String table) {
        return new QueryDependency(table, QueryScope.broadcast());
    }

    /** @return 是否沿用旧全表保护，不能据此跳过 v2 广播转换 */
    public boolean isTable() {
        return scope.kind() == QueryScope.Kind.TABLE;
    }

    boolean isBroadcast() {
        return scope.kind() == QueryScope.Kind.BROADCAST;
    }

    /** @return 可逆且规范的资源身份，不包含调用凭证 */
    public String identity() {
        if (isTable()) {
            return table;
        }
        return isBroadcast() ? "b:" + table : "s:" + table + ":" + scope.identity();
    }

    static boolean validTable(String table) {
        return table != null && table.length() <= 64 && TABLE_NAME.matcher(table).matches();
    }

    static QueryDependency fromIdentity(String identity) {
        Objects.requireNonNull(identity, "查询缓存资源不能为空");
        String[] parts = identity.split(":", -1);
        QueryDependency dependency;
        if (parts.length == 1) {
            dependency = table(identity);
        } else if (parts.length == 2 && "b".equals(parts[0])) {
            dependency = broadcast(parts[1]);
        } else if (parts.length == 4 && "s".equals(parts[0])) {
            QueryScope scope = switch (parts[2]) {
                case "user" -> QueryScope.user(Long.valueOf(parts[3]));
                case "tenant" -> QueryScope.tenant(Long.valueOf(parts[3]));
                default -> throw new IllegalArgumentException("查询缓存资源范围无效");
            };
            dependency = new QueryDependency(parts[1], scope);
        } else if (parts.length == 6 && "s".equals(parts[0])
                && "tenant".equals(parts[2]) && "user".equals(parts[4])) {
            dependency = new QueryDependency(parts[1],
                    QueryScope.tenantUser(Long.valueOf(parts[3]), Long.valueOf(parts[5])));
        } else {
            throw new IllegalArgumentException("查询缓存资源格式无效");
        }
        if (!dependency.identity().equals(identity)) {
            throw new IllegalArgumentException("查询缓存资源不是规范编码");
        }
        return dependency;
    }
}
