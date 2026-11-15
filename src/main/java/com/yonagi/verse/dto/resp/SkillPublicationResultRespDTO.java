package com.yonagi.verse.dto.resp;

/** 未确认不等于事务失败，客户端只能原键原载荷重试。 */
public record SkillPublicationResultRespDTO(
        /** COMMITTED或UNCONFIRMED。 */
        String status,
        /** 已提交的稳定事实。 */
        SkillVersionReceiptRespDTO receipt) {}
