package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.req.SkillImportCreateReqDTO;
import com.yonagi.verse.dto.req.SkillWorkspaceOpenReqDTO;
import com.yonagi.verse.dto.req.SkillWorkspaceSaveReqDTO;
import com.yonagi.verse.dto.resp.SkillFileComparisonRespDTO;
import com.yonagi.verse.dto.resp.SkillFileContentRespDTO;
import com.yonagi.verse.dto.resp.SkillFilesRespDTO;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionChangesRespDTO;
import com.yonagi.verse.dto.resp.SkillVersionPreviewRespDTO;
import com.yonagi.verse.dto.resp.SkillWorkspaceRespDTO;
import com.yonagi.verse.service.skillhub.SkillGithubImportService;
import com.yonagi.verse.service.skillhub.SkillVersionFileService;
import com.yonagi.verse.service.skillhub.SkillVersionWorkspaceService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 唯一待发布区入口，不接受来源配置或基础信息的更新。 */
@RestController
@RequestMapping("/api/v1/skills/{skillId:[0-9]+}/workspace")
@RequiredArgsConstructor
@Validated
public class SkillVersionWorkspaceController {
    private final SkillVersionWorkspaceService workspace;
    private final SkillGithubImportService github;
    private final SkillVersionFileService files;
    private final SkillPublicQueryRateLimiter limiter;

    /**
     * @return 只读恢复当前准备内容
     */
    @GetMapping
    public Result<SkillWorkspaceRespDTO> get(@PathVariable @Positive Long skillId) {
        return Results.success(workspace.get(actor(), skillId));
    }

    /**
     * @return 显式开始准备，活跃内容不会被覆盖
     */
    @PostMapping
    public Result<SkillWorkspaceRespDTO> open(
            @PathVariable @Positive Long skillId,
            @Valid @RequestBody SkillWorkspaceOpenReqDTO input,
            @RequestHeader("Idempotency-Key") String key) {
        return Results.success(workspace.open(actor(), skillId, input, key));
    }

    /**
     * @return 仅保存说明后的修订
     */
    @PutMapping
    public Result<SkillWorkspaceRespDTO> save(
            @PathVariable @Positive Long skillId,
            @Valid @RequestBody SkillWorkspaceSaveReqDTO input) {
        return Results.success(workspace.save(actor(), skillId, input));
    }

    /**
     * @return 按固定来源重新拉取的任务
     */
    @PostMapping("/github-imports")
    public Result<SkillImportTaskRespDTO> github(
            @PathVariable @Positive Long skillId,
            @Valid @RequestBody SkillImportCreateReqDTO input,
            @RequestHeader("Idempotency-Key") String key) {
        return Results.success(github.createUpdate(actor(), skillId, input.revision(), key));
    }

    /**
     * @return 固定的版本编号、来源与摘要
     */
    @GetMapping("/publication-preview")
    public Result<SkillVersionPreviewRespDTO> preview(
            @PathVariable @Positive Long skillId, @RequestParam String type) {
        return Results.success(workspace.preview(actor(), skillId, type));
    }

    /**
     * @return 经过编辑修订与本人权限核对的只读结果
     */
    @GetMapping("/files")
    public Result<SkillFilesRespDTO> files(
            @PathVariable @Positive Long skillId,
            @RequestParam @PositiveOrZero Long revision,
            @RequestParam String type) {
        return Results.success(files.files(actor(), skillId, null, revision, type));
    }

    /**
     * @return 经过编辑修订与本人权限核对的只读结果
     */
    @GetMapping("/files/content")
    public Result<SkillFileContentRespDTO> content(
            @PathVariable @Positive Long skillId,
            @RequestParam @PositiveOrZero Long revision,
            @RequestParam String type,
            @RequestParam @NotBlank @Size(max = 512) String path) {
        return Results.success(files.content(actor(), skillId, null, revision, type, path));
    }

    /**
     * @return 经过编辑修订与本人权限核对的只读结果
     */
    @GetMapping("/changes")
    public Result<SkillVersionChangesRespDTO> changes(
            @PathVariable @Positive Long skillId,
            @RequestParam @PositiveOrZero Long revision,
            @RequestParam String type) {
        return Results.success(files.changes(actor(), skillId, null, revision, type));
    }

    /**
     * @return 经过编辑修订与本人权限核对的只读结果
     */
    @GetMapping("/comparison")
    public Result<SkillFileComparisonRespDTO> comparison(
            @PathVariable @Positive Long skillId,
            @RequestParam @PositiveOrZero Long revision,
            @RequestParam String type,
            @RequestParam @NotBlank @Size(max = 512) String path) {
        return Results.success(files.comparison(actor(), skillId, null, revision, type, path));
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
