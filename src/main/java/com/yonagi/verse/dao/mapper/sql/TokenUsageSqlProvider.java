package com.yonagi.verse.dao.mapper.sql;

import java.util.Map;

/**
 * Token 用量费用聚合 SQL 构建器。
 *
 * @author Yonagi
 */
public final class TokenUsageSqlProvider {

    private static final String AGGREGATE_COLUMNS = """
            COUNT(*) AS request_count,
            COALESCE(SUM(input_tokens), 0) AS input_tokens,
            COALESCE(SUM(cached_input_tokens), 0) AS cached_input_tokens,
            COALESCE(SUM(output_tokens), 0) AS output_tokens,
            COALESCE(SUM(input_tokens), 0) + COALESCE(SUM(output_tokens), 0) AS total_tokens,
            SUM(estimated_cost_fen) AS estimated_cost_fen,
            SUM(CASE WHEN cost_status = 'UNCALCULABLE' THEN 1 ELSE 0 END) AS uncalculable_request_count
            """;

    private TokenUsageSqlProvider() {
    }

    public static String aggregate(Map<String, Object> params) {
        return "SELECT " + AGGREGATE_COLUMNS + baseFromAndWhere(params);
    }

    public static String aggregateTimeseries(Map<String, Object> params) {
        String bucketExpression = switch ((String) params.get("granularity")) {
            case "hour" -> "CAST(DATE_FORMAT(request_started_at, '%Y-%m-%d %H:00:00') AS DATETIME)";
            case "day" -> "CAST(DATE(request_started_at) AS DATETIME)";
            case "week" -> "CAST(DATE_SUB(DATE(request_started_at), "
                    + "INTERVAL WEEKDAY(request_started_at) DAY) AS DATETIME)";
            case "month" -> "CAST(DATE_FORMAT(request_started_at, '%Y-%m-01 00:00:00') AS DATETIME)";
            default -> throw new IllegalArgumentException("不支持的统计粒度");
        };
        return "SELECT " + bucketExpression + " AS bucket_start, " + AGGREGATE_COLUMNS
                + baseFromAndWhere(params) + " GROUP BY bucket_start ORDER BY bucket_start";
    }

    public static String aggregateByDimension(Map<String, Object> params) {
        String dimensionColumn = switch ((String) params.get("dimension")) {
            case "model" -> "service_id";
            case "apiKey" -> "api_key_id";
            default -> throw new IllegalArgumentException("不支持的分组维度");
        };
        return "SELECT " + dimensionColumn + " AS dimension_id, " + AGGREGATE_COLUMNS
                + baseFromAndWhere(params) + " AND " + dimensionColumn + " IS NOT NULL"
                + " GROUP BY " + dimensionColumn;
    }

    private static String baseFromAndWhere(Map<String, Object> params) {
        StringBuilder sql = new StringBuilder("""
                 FROM t_token_usage
                 WHERE tenant_id = #{tenantId}
                   AND cost_status IN ('CALCULATED', 'UNCALCULABLE')
                   AND request_started_at >= #{from}
                   AND request_started_at < #{to}
                """);
        appendOptionalFilter(sql, params, "serviceId", "service_id");
        appendOptionalFilter(sql, params, "apiKeyId", "api_key_id");
        appendOptionalFilter(sql, params, "userId", "user_id");
        return sql.toString();
    }

    private static void appendOptionalFilter(StringBuilder sql, Map<String, Object> params,
                                             String parameterName, String columnName) {
        if (params.get(parameterName) != null) {
            sql.append(" AND ").append(columnName).append(" = #{").append(parameterName).append('}');
        }
    }
}
