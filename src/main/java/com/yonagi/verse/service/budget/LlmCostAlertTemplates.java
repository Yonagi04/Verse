package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.LlmCostAlertEventDO;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/** 受控纯文本模板，名称不能成为 HTML 或模板指令。 */
public final class LlmCostAlertTemplates {
    private LlmCostAlertTemplates() { }
    public static String period(String type, boolean warning) {
        return switch (type) { case "DAY" -> warning ? "每日" : "本日"; case "WEEK" -> warning ? "每周" : "本周"; default -> warning ? "每月" : "本月"; };
    }
    public static String yuan(BigDecimal fen) {
        if (fen.signum() > 0 && fen.compareTo(BigDecimal.ONE) < 0) return "<0.01";
        return fen.movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
    public static String safe(String value) {
        return (value == null ? "" : value).replaceAll("[\\p{Cntrl}]", " ").replace("${", "＄{");
    }
    private static boolean tenant(LlmCostAlertEventDO e) { return "TENANT".equals(e.getScopeType()); }
    private static String objectName(LlmCostAlertEventDO e) { return safe(tenant(e) ? e.getScopeName() : e.getServiceName()); }
    public static String title(LlmCostAlertEventDO e) {
        return (tenant(e) ? "租户" : "模型") + ("LIMIT".equals(e.getEventType()) ? "成本限额已达到：" : "成本限额预警：") + objectName(e);
    }
    public static Map<String,String> sms(LlmCostAlertEventDO e) {
        var values = new java.util.LinkedHashMap<String,String>();
        values.put("tenantName", safe(e.getTenantName())); if (!tenant(e)) values.put("serviceName", safe(e.getServiceName()));
        values.put("period", period(e.getPeriodType(), "WARNING".equals(e.getEventType())));
        if ("WARNING".equals(e.getEventType())) values.put("remainingYuan", yuan(e.getRemainingCostFen()));
        return values;
    }
    public static String publicBody(LlmCostAlertEventDO e, String retryAt) {
        if ("WARNING".equals(e.getEventType())) return objectName(e) + " 触发" + period(e.getPeriodType(), true)
                + "成本限额 " + e.getThresholdPercent() + "% 预警。当前周期仍可继续调用，请关注消耗或联系管理员。";
        return objectName(e) + " " + period(e.getPeriodType(), false) + "调用成本已达到限额，新的 API 和 PlayGround 调用暂不可用。预计恢复："
                + retryAt + "（GMT+8）。请联系管理员调整限额或等待相关周期重置；仍需满足 API Key、模型等其他独立限制。";
    }
    public static String emailBody(LlmCostAlertEventDO e) {
        return publicBody(e, String.valueOf(e.getRetryAt())) + "\n周期：" + e.getPeriodStart() + " 至 " + e.getPeriodEnd()
                + "（GMT+8）\n限额：¥" + yuan(e.getLimitFen()) + "\n已用：¥" + yuan(e.getUsedCostFen())
                + "\n剩余：¥" + yuan(e.getRemainingCostFen()) + "\n触发时预计恢复：" + e.getRetryAt() + "（GMT+8）\n触发时间：" + e.getTriggeredAt()
                + "（GMT+8）\n" + (tenant(e) ? "请登录租户设置调整租户限额。" : "请登录模型配置页调整限额。") + "手动停用或故障状态仍可能阻止调用。";
    }
}
