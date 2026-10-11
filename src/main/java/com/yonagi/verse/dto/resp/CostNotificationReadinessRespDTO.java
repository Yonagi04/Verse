package com.yonagi.verse.dto.resp;
import java.util.List;
public record CostNotificationReadinessRespDTO(
        /** 平台配置检查，不能当作供应商送达证明。 */ List<Channel> channels,
        /** 有效管理员联系方式缺口。 */ Administrators administrators,
        /** 查询时刻。 */ String asOf) {
    public record Channel(/** SMS 或 EMAIL。 */ String channel, /** 是否已配置。 */ boolean configured, /** 缺口原因。 */ String reasonCode) { }
    public record Administrators(/** 管理员数。 */ int total, /** 无手机号人数。 */ long missingPhoneCount, /** 无邮箱人数。 */ long missingEmailCount) { }
}
