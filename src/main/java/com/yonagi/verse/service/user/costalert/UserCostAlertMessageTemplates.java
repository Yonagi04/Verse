package com.yonagi.verse.service.user.costalert;

import com.yonagi.verse.dao.entity.UserCostAlertEventDO;
import java.math.RoundingMode;
import java.net.URI;
import java.util.*;

public final class UserCostAlertMessageTemplates {
    private UserCostAlertMessageTemplates() { }
    public static String title(UserCostAlertEventDO event) { return "个人"+("DAY".equals(event.getPeriodType()) ? "每日" : "每周")+"费用预警"; }
    public static String body(UserCostAlertEventDO event) {
        return "你的"+("DAY".equals(event.getPeriodType()) ? "本日" : "本周")+"个人预估费用已达到 ¥"+yuan(event.getKnownCostFen())
                +"，触发 ¥"+yuan(event.getThresholdFen())+" 的预警设置。统计范围为所有租户，仅用于提醒。\n周期："
                +event.getPeriodStart()+" 至 "+event.getPeriodEnd()+"（结束不含，北京时间）。"
                +(Boolean.TRUE.equals(event.getCostComplete()) ? "" : "\n部分费用或历史尚不完整，金额为已知累计。" );
    }
    public static Map<String,String> sms(UserCostAlertEventDO event) {
        return Map.of("period","DAY".equals(event.getPeriodType()) ? "日" : "周","start",event.getPeriodStart().toLocalDate().toString(),
                "end",event.getPeriodEnd().toLocalDate().toString(),"amount",yuan(event.getKnownCostFen()),"threshold",yuan(event.getThresholdFen()));
    }
    public static String link(String frontendBaseUrl) {
        URI origin=URI.create(frontendBaseUrl);
        if (!List.of("http","https").contains(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null) throw new IllegalStateException("INVALID_FRONTEND_ORIGIN");
        return origin.getScheme()+"://"+origin.getRawAuthority()+"/profile?panel=cost-alert";
    }
    private static String yuan(java.math.BigDecimal amount) { return amount.movePointLeft(2).setScale(2,RoundingMode.HALF_UP).toPlainString(); }
}
