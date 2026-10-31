package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillDownloadGrantDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillDownloadGrantMapper;
import com.yonagi.verse.dto.resp.SkillDownloadGrantRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** 只保存安全随机下载能力的摘要，无统计、计费或原令牌重建承诺。 */
@Service
@RequiredArgsConstructor
public class SkillDownloadGrantService {
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_LIVE_GRANTS = 30;
    private static final int GRANT_SECONDS = 60;
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{64}");
    private final SkillDownloadGrantMapper grantMapper;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param subjectHash 限流主体，不是用户授权
     * @param resource 已通过实时可用性确认的固定资源
     * @return 新短效授权；调用方在单机临界区内等待事务提交再释放
     */
    @Transactional(rollbackFor = Exception.class)
    public SkillDownloadGrantRespDTO create(String subjectHash, SkillDownloadResource resource) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        Long active = grantMapper.selectCount(Wrappers.lambdaQuery(SkillDownloadGrantDO.class)
                .eq(SkillDownloadGrantDO::getSubjectHash, subjectHash).gt(SkillDownloadGrantDO::getExpiresAt, now));
        if (active >= MAX_LIVE_GRANTS) {
            throw new ClientException(SkillErrorCodeEnum.RATE_LIMITED);
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        SkillDownloadGrantDO grant = new SkillDownloadGrantDO();
        grant.setGrantId(SnowflakeIdUtil.nextId());
        grant.setTokenHash(DigestUtil.sha256Hex(token));
        grant.setVersionId(resource.versionId());
        grant.setSubjectHash(subjectHash);
        grant.setExpiresAt(now.plusSeconds(GRANT_SECONDS));
        grant.setCreateTime(now);
        grant.setUpdateTime(now);
        grantMapper.insert(grant);
        return new SkillDownloadGrantRespDTO("/api/v1/skills/downloads/" + token,
                grant.getExpiresAt().atOffset(ZoneOffset.UTC), resource.sha256(), resource.bytes());
    }

    /**
     * @param token 不记录日志的原始短效能力
     * @return 已绑定固定版本；过期/未知统一不可用
     */
    public Long resolve(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            throw new ClientException(SkillErrorCodeEnum.VERSION_UNAVAILABLE);
        }
        SkillDownloadGrantDO grant = grantMapper.selectOne(Wrappers.lambdaQuery(SkillDownloadGrantDO.class)
                .eq(SkillDownloadGrantDO::getTokenHash, DigestUtil.sha256Hex(token))
                .gt(SkillDownloadGrantDO::getExpiresAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (grant == null) {
            throw new ClientException(SkillErrorCodeEnum.VERSION_UNAVAILABLE);
        }
        return grant.getVersionId();
    }
}
