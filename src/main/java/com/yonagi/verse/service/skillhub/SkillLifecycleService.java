package com.yonagi.verse.service.skillhub;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.enums.SkillStatusEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillAuditRecordDO;
import com.yonagi.verse.dao.entity.skillhub.SkillDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillAuditRecordMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillMapper;
import com.yonagi.verse.dao.mapper.skillhub.SkillOwnerQueryMapper;
import com.yonagi.verse.dto.req.SkillUnpublishReqDTO;
import com.yonagi.verse.dto.resp.SkillMyPublicationRespDTO;
import com.yonagi.verse.dto.resp.SkillPageRespDTO;
import com.yonagi.verse.service.user.AccountAvailabilityService;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 个人管理与作者下架，保留已批准事实及私有包。读取不缓存，立即反映发布状态。 */
@Service
@RequiredArgsConstructor
public class SkillLifecycleService {
    private static final int PAGE_SIZE = 12;
    private static final int MAX_PAGE = 10000;
    private static final String UNPUBLISH_COMMAND = "UNPUBLISH";
    private final SkillAccessPolicy accessPolicy;
    private final AccountAvailabilityService accountAvailabilityService;
    private final SkillOwnerQueryMapper ownerQueryMapper;
    private final SkillMapper skillMapper;
    private final SkillAuditRecordMapper auditMapper;
    private final SkillIdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    /**
     * @param userId 可信账号
     * @param page 页码，从1开始
     * @return 本人公开和未公开内容；没有身份或资源时为空页
     */
    public SkillPageRespDTO<SkillMyPublicationRespDTO> mine(Long userId, int page) {
        if (page < 1 || page > MAX_PAGE) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        if (!accountAvailabilityService.isAccountActive(userId)) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        long total = ownerQueryMapper.countOwned(userId);
        if (total == 0) {
            return new SkillPageRespDTO<>(page, PAGE_SIZE, 0L, List.of());
        }
        List<SkillMyPublicationRespDTO> items = ownerQueryMapper
                .selectOwnedPage(userId, PAGE_SIZE, (page - 1) * PAGE_SIZE).stream()
                .map(row -> new SkillMyPublicationRespDTO(row.getSkillId().toString(),
                        row.getDraftId() == null ? null : row.getDraftId().toString(), row.getName(), row.getSlug(),
                        row.getStatus(), row.getDraftStatus(), row.getJobStatus(), row.getVersionLabel(),
                        row.getRevision(), row.getUpdateTime().atOffset(ZoneOffset.UTC))).toList();
        return new SkillPageRespDTO<>(page, PAGE_SIZE, total, items);
    }

    /**
     * @param userId 可信账号
     * @param skillId 本人公开Skill
     * @param input 修订及主动下架原因
     * @param key 结果未知时复用的UUID
     * @return 下架后修订；不能解除平台限制或重新公开
     */
    @Transactional(rollbackFor = Exception.class)
    public Long unpublish(Long userId, Long skillId, SkillUnpublishReqDTO input, String key) {
        if (input == null || !validator.validate(input).isEmpty()) {
            throw new ClientException(SkillErrorCodeEnum.INVALID_INPUT);
        }
        SkillDO skill = accessPolicy.lockSkill(userId, skillId).skill();
        List<Object> request = List.of(skillId, input);
        Long replay = idempotencyService.replay(userId, UNPUBLISH_COMMAND, key, request, Long.class);
        if (replay != null) {
            return replay;
        }
        if (!SkillStatusEnum.ACTIVE.name().equals(skill.getStatus()) || !input.revision().equals(skill.getRevision())) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long revision = skill.getRevision() + 1;
        int changed = skillMapper.update(null, Wrappers.lambdaUpdate(SkillDO.class)
                .eq(SkillDO::getSkillId, skillId).eq(SkillDO::getRevision, input.revision())
                .eq(SkillDO::getStatus, SkillStatusEnum.ACTIVE.name())
                .set(SkillDO::getStatus, SkillStatusEnum.OWNER_UNPUBLISHED.name())
                .set(SkillDO::getRevision, revision).set(SkillDO::getUpdateTime, now));
        if (changed != 1) {
            throw new ClientException(SkillErrorCodeEnum.CONFLICT);
        }
        SkillAuditRecordDO audit = new SkillAuditRecordDO();
        audit.setAuditId(SnowflakeIdUtil.nextId());
        audit.setAction(UNPUBLISH_COMMAND);
        audit.setActorId(userId);
        audit.setTargetId(skillId);
        try {
            audit.setDetailsJson(objectMapper.writeValueAsString(input));
        } catch (JsonProcessingException exception) {
            throw new ServerException("Skill下架审计不可记录", exception, SkillErrorCodeEnum.UNAVAILABLE);
        }
        audit.setCreateTime(now);
        audit.setUpdateTime(now);
        auditMapper.insert(audit);
        idempotencyService.record(userId, UNPUBLISH_COMMAND, key, request, skillId, revision);
        return revision;
    }
}
