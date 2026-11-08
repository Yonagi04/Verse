package com.yonagi.verse.common.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 批次凭证只适用于当前公开的补账操作，新增接口不会自动继承该权限。 */
public final class CostReconciliationSessionRoutes {
    public static final String BASE_PATH = "/api/v1/cost-reconciliation-session";
    public static final String HANDOFF_PATH = BASE_PATH + "/handoffs";
    public static final String EXCHANGE_PATH = BASE_PATH + "/exchanges";
    public static final String PARENT_IDENTITY_ATTRIBUTE = "verse.reconciliation.parentIdentity";
    public static final String BATCHES_PREFIX = "/api/v1/cost-reconciliation-batches/";
    private static final String CASES = BATCHES_PREFIX + "([A-Za-z][A-Za-z0-9]{7})/cases";
    private static final String CASE_ID = "/[1-9][0-9]{0,18}";
    private static final Pattern READ = Pattern.compile(CASES + "(?:" + CASE_ID + ")?");
    private static final Pattern RESOLVE = Pattern.compile(CASES + CASE_ID + "/resolve");

    private CostReconciliationSessionRoutes() {
    }

    /** 返回受限凭证可访问的批次码，其他方法及路径返回空。 */
    public static String scopedBatch(String method, String path) {
        Pattern allowed = switch (method) {
            case "GET" -> READ;
            case "POST" -> RESOLVE;
            case null, default -> null;
        };
        if (allowed == null) {
            return null;
        }
        Matcher matcher = allowed.matcher(path);
        return matcher.matches() ? matcher.group(1) : null;
    }
}
