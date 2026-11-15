package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.req.SkillVersionPublishReqDTO;
import com.yonagi.verse.dto.resp.SkillFileComparisonRespDTO;
import com.yonagi.verse.dto.resp.SkillFileContentRespDTO;
import com.yonagi.verse.dto.resp.SkillFilesRespDTO;
import com.yonagi.verse.dto.resp.SkillPublicationResultRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionChangesRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionPageRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionReceiptRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionRespDTO;
import com.yonagi.verse.service.skillhub.SkillVersionFileService;
import com.yonagi.verse.service.skillhub.SkillVersionPublicationService;
import com.yonagi.verse.service.skillhub.SkillVersionQueryService;
import com.yonagi.verse.service.skillhub.port.SkillPublicQueryRateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import lombok.RequiredArgsConstructor;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 私人版本历史和统一发布入口，历史文件不提供下载授权。 */
@RestController
@RequestMapping("/api/v1/skills/{skillId:[0-9]+}")
@RequiredArgsConstructor
@Validated
public class SkillVersionController {
    private final SkillVersionQueryService history;
    private final SkillVersionPublicationService publication;
    private final SkillVersionFileService files;
    private final SkillPublicQueryRateLimiter limiter;

    /**
     * @return 固定头部的完整私人时间线
     */
    @GetMapping("/versions")
    public Result<SkillVersionPageRespDTO> timeline(
            @PathVariable @Positive Long skillId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @PositiveOrZero Long headSequence) {
        return Results.success(history.timeline(actor(), skillId, page, size, headSequence));
    }

    /**
     * @return 节点及当时基础信息
     */
    @GetMapping("/versions/{versionId:[0-9]+}")
    public Result<SkillVersionRespDTO> detail(
            @PathVariable @Positive Long skillId, @PathVariable @Positive Long versionId) {
        return Results.success(history.detail(actor(), skillId, versionId));
    }

    /**
     * @return 成功事实的稳定回执，重试不重复追加
     */
    @PostMapping("/versions")
    public Result<SkillVersionReceiptRespDTO> publish(
            @PathVariable @Positive Long skillId,
            @Valid @RequestBody SkillVersionPublishReqDTO input,
            @RequestHeader("Idempotency-Key") String key) {
        return Results.success(publication.publish(actor(), skillId, input, key));
    }

    /**
     * @return 已提交回执或尚未确认
     */
    @GetMapping("/publication-results/{publishKey}")
    public Result<SkillPublicationResultRespDTO> result(
            @PathVariable @Positive Long skillId, @PathVariable @Size(max = 36) String publishKey) {
        return Results.success(publication.result(actor(), skillId, publishKey));
    }

    /**
     * @return 仅持有者可访问的历史文件结果
     */
    @GetMapping("/versions/{versionId:[0-9]+}/history-files")
    public Result<SkillFilesRespDTO> files(
            @PathVariable @Positive Long skillId, @PathVariable @Positive Long versionId) {
        return Results.success(files.files(actor(), skillId, versionId, null, null));
    }

    /**
     * @return 仅持有者可访问的历史文件结果
     */
    @GetMapping("/versions/{versionId:[0-9]+}/history-files/content")
    public Result<SkillFileContentRespDTO> content(
            @PathVariable @Positive Long skillId,
            @PathVariable @Positive Long versionId,
            @RequestParam @NotBlank @Size(max = 512) String path) {
        return Results.success(files.content(actor(), skillId, versionId, null, null, path));
    }

    /**
     * @return 仅持有者可访问的历史文件结果
     */
    @GetMapping("/versions/{versionId:[0-9]+}/changes")
    public Result<SkillVersionChangesRespDTO> changes(
            @PathVariable @Positive Long skillId, @PathVariable @Positive Long versionId) {
        return Results.success(files.changes(actor(), skillId, versionId, null, null));
    }

    /**
     * @return 仅持有者可访问的历史文件结果
     */
    @GetMapping("/versions/{versionId:[0-9]+}/comparison")
    public Result<SkillFileComparisonRespDTO> comparison(
            @PathVariable @Positive Long skillId,
            @PathVariable @Positive Long versionId,
            @RequestParam @NotBlank @Size(max = 512) String path) {
        return Results.success(files.comparison(actor(), skillId, versionId, null, null, path));
    }

    /** 私人响应禁止缓存，读取沿用有界频控。 */
    @ModelAttribute
    public void privateResponse(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "private, no-store");
        if ("GET".equals(request.getMethod())) {
            limiter.check(cn.hutool.crypto.digest.DigestUtil.sha256Hex(request.getRemoteAddr()));
        }
    }

    private Long actor() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.getUserId() == null || context.getApiKeyId() != null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return context.getUserId();
    }
}
