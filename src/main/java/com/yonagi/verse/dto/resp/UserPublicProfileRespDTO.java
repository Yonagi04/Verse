package com.yonagi.verse.dto.resp;


/** 跨领域公开资料，不包含账号隐私。 */
public record UserPublicProfileRespDTO(
        /** 公开账号标识。 */ String userId,
        /** 当前公开名称或匿名占位。 */ String displayName,
        /** 当前头像地址，缺失时为空。 */ String avatarUrl) {
}
