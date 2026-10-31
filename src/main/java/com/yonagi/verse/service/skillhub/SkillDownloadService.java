package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dao.mapper.skillhub.SkillDownloadQueryMapper;
import com.yonagi.verse.dto.resp.SkillDownloadGrantRespDTO;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import com.yonagi.verse.service.skillhub.port.SkillDownloadRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** 领取、读取私有字节、最终重查授权；两次授权之间的下架不能被旧grant绕过。 */
@Service
@RequiredArgsConstructor
public class SkillDownloadService {
    private final SkillDownloadQueryMapper queryMapper;
    private final SkillDownloadAuthorizationService authorizationService;
    private final SkillDownloadGrantService grantService;
    private final SkillDownloadRateLimiter rateLimiter;
    private final SkillArtifactStore artifactStore;
    // 当前仅支持单开发实例，临界区包住回执事务提交，限制跨作者的同主体活跃授权。
    private final Semaphore grantCreation = new Semaphore(1);
    private final Semaphore transfers = new Semaphore(2);

    /**
     * @param skillId 公开Skill
     * @param versionId 固定版本
     * @param subject 主体摘要
     * @param ip IP摘要
     * @return 短效能力
     */
    public SkillDownloadGrantRespDTO issue(Long skillId, Long versionId, String subject, String ip) {
        rateLimiter.check(subject, ip);
        if (!grantCreation.tryAcquire()) {
            throw new ClientException(SkillErrorCodeEnum.RATE_LIMITED);
        }
        try {
            if (queryMapper.selectAvailable(skillId, versionId) == null) {
                throw new ClientException(SkillErrorCodeEnum.VERSION_UNAVAILABLE);
            }
            SkillDownloadResource resource = authorizationService.authorize(versionId);
            return grantService.create(subject, resource);
        } finally {
            grantCreation.release();
        }
    }

    /**
     * @param token 短效能力，不是S3地址
     * @param subject 当前限流主体
     * @param ip 当前实际IP摘要
     * @param transfer 传输回调，结束前持有并发预算，防止慢连接堆积多个完整ZIP
     */
    public void download(String token, String subject, String ip, Consumer<SkillDownloadPayload> transfer) {
        rateLimiter.check(subject, ip);
        if (!transfers.tryAcquire()) {
            throw new ClientException(SkillErrorCodeEnum.RATE_LIMITED);
        }
        try {
            Long versionId = grantService.resolve(token);
            SkillDownloadResource resource = authorizationService.authorize(versionId);
            byte[] bytes = artifactStore.get(resource.archiveKey(), (int) SkillPackageLimitConstant.MAX_ARCHIVE_BYTES);
            if (bytes.length != resource.bytes() || !DigestUtil.sha256Hex(bytes).equals(resource.sha256())) {
                throw new ServerException("Skill归档字节校验失败", SkillErrorCodeEnum.UNAVAILABLE);
            }
            grantService.resolve(token);
            SkillDownloadResource current = authorizationService.authorize(versionId);
            if (!resource.equals(current)) {
                throw new ClientException(SkillErrorCodeEnum.VERSION_UNAVAILABLE);
            }
            transfer.accept(new SkillDownloadPayload(resource.filename(), bytes, resource.sha256()));
        } finally {
            transfers.release();
        }
    }
}
