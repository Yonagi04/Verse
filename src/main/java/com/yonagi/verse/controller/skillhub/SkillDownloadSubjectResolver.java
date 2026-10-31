package com.yonagi.verse.controller.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import org.springframework.stereotype.Component;

/** 下载限流仅使用可信登录账号，不再签发游客身份或授予访问能力。 */
@Component
public class SkillDownloadSubjectResolver {
    /**
     * @return 当前有效账号的稳定限流摘要；不能由API Key或请求字段冒充
     * @throws ClientException 没有可信账号上下文
     */
    public String resolve() {
        UserContext actor = UserContextHolder.get();
        if (actor == null || actor.getUserId() == null || actor.getApiKeyId() != null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return DigestUtil.sha256Hex("account:" + actor.getUserId());
    }
}
