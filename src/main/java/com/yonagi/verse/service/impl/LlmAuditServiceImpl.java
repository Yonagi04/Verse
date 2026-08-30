package com.yonagi.verse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.LlmAuditErrorCodeEnum;
import com.yonagi.verse.common.enums.RoleEnum;
import com.yonagi.verse.common.enums.TenantErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.dao.entity.LlmAuditLogDO;
import com.yonagi.verse.dao.entity.UserDO;
import com.yonagi.verse.dao.mapper.LlmAuditLogMapper;
import com.yonagi.verse.dao.mapper.UserMapper;
import com.yonagi.verse.dto.resp.LlmAuditDetailRespDTO;
import com.yonagi.verse.dto.resp.LlmAuditListRespDTO;
import com.yonagi.verse.service.LlmAuditService;
import com.yonagi.verse.service.UserTenantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * LLM 调用审计查询实现 — 列表按租户 + 角色隔离分页，详情回源 S3 取完整内容。
 *
 * @author Yonagi
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmAuditServiceImpl implements LlmAuditService {

    private final LlmAuditLogMapper llmAuditLogMapper;
    private final UserMapper userMapper;
    private final UserTenantService userTenantService;
    private final S3Client s3Client;

    @Value("${verse.s3.bucket}")
    private String bucket;

    @Override
    public LlmAuditListRespDTO listAudit(UserContext ctx, Long tenantId, Integer pageNum, Integer pageSize, Long userId) {
        validateMembership(ctx, tenantId);
        boolean isAdmin = isAdmin(ctx);

        LambdaQueryWrapper<LlmAuditLogDO> wrapper = Wrappers.lambdaQuery(LlmAuditLogDO.class)
                .eq(LlmAuditLogDO::getTenantId, tenantId);
        if (!isAdmin) {
            // MEMBER 强制只能查自己，忽略 userId 入参
            wrapper.eq(LlmAuditLogDO::getUserId, ctx.getUserId());
        } else if (userId != null) {
            // ADMIN/SUPER_ADMIN 可选按用户筛选
            wrapper.eq(LlmAuditLogDO::getUserId, userId);
        }
        wrapper.orderByDesc(LlmAuditLogDO::getCreateTime);

        Page<LlmAuditLogDO> page = llmAuditLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        Map<Long, String> usernameMap = loadUsernames(page.getRecords());

        List<LlmAuditListRespDTO.LlmAuditInfo> infos = page.getRecords().stream()
                .map(log -> new LlmAuditListRespDTO.LlmAuditInfo()
                        .setId(log.getId())
                        .setRequestId(log.getRequestId())
                        .setUserId(log.getUserId())
                        .setUsername(usernameMap.get(log.getUserId()))
                        .setModel(log.getModel())
                        .setPromptPreview(log.getPromptPreview())
                        .setResponsePreview(log.getResponsePreview())
                        .setPromptTokens(log.getPromptTokens())
                        .setCompletionTokens(log.getCompletionTokens())
                        .setTotalTokens(log.getTotalTokens())
                        .setLatencyMs(log.getLatencyMs())
                        .setStatus(log.getStatus())
                        .setErrorCode(log.getErrorCode())
                        .setCreateTime(log.getCreateTime()))
                .toList();

        return new LlmAuditListRespDTO()
                .setAuditList(infos)
                .setTotal(page.getTotal())
                .setTotalPages(page.getPages())
                .setPage(pageNum)
                .setPageSize(pageSize);
    }

    @Override
    public LlmAuditDetailRespDTO getAuditDetail(UserContext ctx, Long tenantId, Long auditId) {
        validateMembership(ctx, tenantId);
        boolean isAdmin = isAdmin(ctx);

        LambdaQueryWrapper<LlmAuditLogDO> wrapper = Wrappers.lambdaQuery(LlmAuditLogDO.class)
                .eq(LlmAuditLogDO::getId, auditId)
                .eq(LlmAuditLogDO::getTenantId, tenantId);
        if (!isAdmin) {
            // MEMBER 只能取自己名下的记录，取不到时统一返回「记录不存在」，不暴露存在性
            wrapper.eq(LlmAuditLogDO::getUserId, ctx.getUserId());
        }
        LlmAuditLogDO log = llmAuditLogMapper.selectOne(wrapper);
        if (log == null) {
            throw new ClientException(LlmAuditErrorCodeEnum.AUDIT_LOG_NOT_FOUND);
        }

        LlmAuditDetailRespDTO dto = new LlmAuditDetailRespDTO();
        dto.setId(log.getId());
        dto.setRequestId(log.getRequestId());
        dto.setUserId(log.getUserId());
        dto.setUsername(resolveUsername(log.getUserId()));
        dto.setModel(log.getModel());
        dto.setPromptPreview(log.getPromptPreview());
        dto.setResponsePreview(log.getResponsePreview());
        dto.setPromptTokens(log.getPromptTokens());
        dto.setCompletionTokens(log.getCompletionTokens());
        dto.setTotalTokens(log.getTotalTokens());
        dto.setLatencyMs(log.getLatencyMs());
        dto.setStatus(log.getStatus());
        dto.setErrorCode(log.getErrorCode());
        dto.setCreateTime(log.getCreateTime());
        dto.setPrompt(readContent(log.getPromptObjectKey()));
        dto.setResponse(readContent(log.getResponseObjectKey()));
        return dto;
    }

    private Map<Long, String> loadUsernames(List<LlmAuditLogDO> records) {
        Set<Long> userIds = records.stream()
                .map(LlmAuditLogDO::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectList(Wrappers.lambdaQuery(UserDO.class).in(UserDO::getUserId, userIds))
                .stream()
                .collect(Collectors.toMap(UserDO::getUserId, UserDO::getUsername, (a, b) -> a));
    }

    private String resolveUsername(Long userId) {
        if (userId == null) {
            return null;
        }
        UserDO user = userMapper.selectOne(Wrappers.lambdaQuery(UserDO.class).eq(UserDO::getUserId, userId));
        return user != null ? user.getUsername() : null;
    }

    /**
     * 从 S3 读取完整内容；objectKey 缺失返回 null，读取失败抛服务端错误。
     */
    private String readContent(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return null;
        }
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .build())
                    .asUtf8String();
        } catch (Exception e) {
            log.error("[llm-audit] 读取 S3 失败: key={}", objectKey, e);
            throw new ServerException(LlmAuditErrorCodeEnum.AUDIT_S3_READ_FAILED);
        }
    }

    private void validateMembership(UserContext ctx, Long tenantId) {
        if (!userTenantService.isUserJoinedTenant(ctx.getUserId(), tenantId)) {
            throw new ClientException(TenantErrorCodeEnum.TENANT_NOT_JOINED);
        }
    }

    private boolean isAdmin(UserContext ctx) {
        String role = ctx.getRole();
        if (role == null) {
            return false;
        }
        return RoleEnum.valueOf(role).isAdmin();
    }
}
