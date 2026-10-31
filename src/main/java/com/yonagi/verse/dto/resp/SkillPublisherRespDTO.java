package com.yonagi.verse.dto.resp;

/** 平台公开发布身份，不包含登录用户名或联系方式。 */
public record SkillPublisherRespDTO(
        /** 发布者标识，字符串避免浏览器精度丢失。 */ String publisherId,
        /** 首次确认后固定的公开handle。 */ String handle,
        /** 用户主动确认公开的名称。 */ String publicName) {
}
