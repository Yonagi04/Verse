package com.yonagi.verse.service.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.util.SnowflakeIdUtil;
import com.yonagi.verse.dao.entity.skillhub.SkillIdempotencyRecordDO;
import com.yonagi.verse.dao.mapper.skillhub.SkillIdempotencyRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Pattern;

/** 幂等回执随原用例事务提交，账号/资源锁由调用用例先取得。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
public class SkillIdempotencyService {
    private static final Pattern UUID_KEY = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final int RETENTION_DAYS = 7;
    private final SkillIdempotencyRecordMapper recordMapper;
    private final ObjectMapper objectMapper;

    /**
     * @param userId 可信账号主体
     * @param command 领域命令类型，由Service提供
     * @param key 客户端UUID
     * @param payload 规范请求，必须包含目标资源及修订
     * @param resultType 原回执的固定类型
     * @return 原回执；首次请求时null，同key换载荷抛冲突
     */
    public <T> T replay(Long userId, String command, String key, Object payload, Class<T> resultType) {
        SkillIdempotencyRecordDO record = recordMapper.selectOne(Wrappers.lambdaQuery(SkillIdempotencyRecordDO.class)
                .eq(SkillIdempotencyRecordDO::getSubjectScope, userId.toString())
                .eq(SkillIdempotencyRecordDO::getCommandType, command)
                .eq(SkillIdempotencyRecordDO::getIdempotencyKey, normalizeKey(key)));
        if (record == null) {
            return null;
        }
        if (!record.getRequestHash().equals(hash(payload))) {
            throw new ClientException(SkillErrorCodeEnum.IDEMPOTENCY_CONFLICT);
        }
        try {
            return objectMapper.readValue(record.getResponseJson(), resultType);
        } catch (JsonProcessingException exception) {
            throw new ServerException("Skill幂等回执不可读取", exception, SkillErrorCodeEnum.UNAVAILABLE);
        }
    }

    /**
     * @param userId 可信账号主体
     * @param command 领域命令类型
     * @param key 原客户端UUID
     * @param payload 与重放检查相同的规范请求
     * @param resourceId 长期资源标识，业务表唯一约束继续兜底
     * @param response 用例提交后返回的稳定结果
     */
    public void record(Long userId, String command, String key, Object payload, Long resourceId, Object response) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SkillIdempotencyRecordDO record = new SkillIdempotencyRecordDO();
        record.setCommandId(SnowflakeIdUtil.nextId());
        record.setSubjectScope(userId.toString());
        record.setCommandType(command);
        record.setIdempotencyKey(normalizeKey(key));
        record.setRequestHash(hash(payload));
        record.setResourceId(resourceId);
        try {
            record.setResponseJson(objectMapper.writeValueAsString(response));
        } catch (JsonProcessingException exception) {
            throw new ServerException("Skill幂等回执序列化失败", exception, SkillErrorCodeEnum.UNAVAILABLE);
        }
        record.setExpiresAt(now.plusDays(RETENTION_DAYS));
        record.setCreateTime(now);
        record.setUpdateTime(now);
        recordMapper.insert(record);
    }

    private String normalizeKey(String key) {
        if (key == null || !UUID_KEY.matcher(key).matches()) {
            throw new ClientException("请求需要有效UUID幂等标识", SkillErrorCodeEnum.INVALID_INPUT);
        }
        return key.toLowerCase(Locale.ROOT);
    }

    private String hash(Object payload) {
        try {
            return DigestUtil.sha256Hex(objectMapper.writeValueAsBytes(payload));
        } catch (JsonProcessingException exception) {
            throw new ServerException("Skill命令摘要不可生成", exception, SkillErrorCodeEnum.UNAVAILABLE);
        }
    }
}
