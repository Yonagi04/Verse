package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.constant.SkillPackageLimitConstant;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import com.yonagi.verse.service.skillhub.port.SkillArtifactStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.concurrent.Semaphore;

/** 原始ZIP有界接收及S3编排，远程写入不占数据库事务。 */
@Service
@RequiredArgsConstructor
public class SkillImportService {
    private static final int MAX_PARALLEL_UPLOADS = 2;
    private final SkillImportTaskService taskService;
    private final SkillArtifactStore artifactStore;
    private final Semaphore uploads = new Semaphore(MAX_PARALLEL_UPLOADS);

    /**
     * @param userId 可信账号
     * @param jobId 本人等待上传任务
     * @param bytes 已在HTTP边界完成总期限和大小检查的原始ZIP
     * @return 仅表示已持久化排队，不伪装为检查或发布成功
     */
    public SkillImportTaskRespDTO upload(Long userId, Long jobId, byte[] bytes) {
        if (!uploads.tryAcquire()) {
            throw new ClientException(SkillErrorCodeEnum.RATE_LIMITED);
        }
        SkillUploadClaim claim = null;
        try {
            claim = taskService.beginUpload(userId, jobId);
            if (bytes == null || bytes.length == 0 || bytes.length > SkillPackageLimitConstant.MAX_ARCHIVE_BYTES) {
                throw new ClientException(SkillErrorCodeEnum.LIMIT_EXCEEDED);
            }
            artifactStore.put(claim.sourceKey(), bytes);
            taskService.completeUpload(claim, DigestUtil.sha256Hex(bytes), bytes.length);
            return taskService.get(userId, jobId);
        } finally {
            try {
                if (claim != null) {
                    taskService.uploadFailed(claim);
                }
            } finally {
                uploads.release();
            }
        }
    }
}
