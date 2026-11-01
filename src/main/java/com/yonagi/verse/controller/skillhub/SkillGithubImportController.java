package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.req.SkillGithubImportCreateReqDTO;
import com.yonagi.verse.dto.req.SkillGithubSelectionReqDTO;
import com.yonagi.verse.dto.req.SkillGithubSourceConfirmReqDTO;
import com.yonagi.verse.dto.resp.SkillGithubCandidatesRespDTO;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import com.yonagi.verse.service.skillhub.SkillGithubImportService;
import com.yonagi.verse.service.skillhub.SkillDraftFileService;
import com.yonagi.verse.dto.resp.SkillDraftFilesRespDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 个人公开GitHub导入命令，不要求当前租户或GitHub账号绑定。 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
@Validated
public class SkillGithubImportController {
    private final SkillGithubImportService service;
    private final SkillDraftFileService files;

    /** @return 本人当前已检查制品的文件元数据，不提供原始源归档 */
    @GetMapping("/drafts/{draftId:[0-9]+}/files")
    public Result<SkillDraftFilesRespDTO> files(@PathVariable @Positive Long draftId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "50") int size) {
        return Results.success(files.files(actor(), draftId, page, size));
    }

    /** @return 已持久化解析任务，不伪装为获取或检查已完成 */
    @PostMapping("/drafts/{draftId:[0-9]+}/github-imports")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<SkillImportTaskRespDTO> create(@PathVariable @Positive Long draftId,
                                                @RequestBody @Valid SkillGithubImportCreateReqDTO input,
                                                @RequestHeader("Idempotency-Key") String key) {
        return Results.success(service.create(actor(), draftId, input, key));
    }

    /** @return 用户选择并冻结的服务器来源进入获取队列 */
    @PostMapping("/imports/{jobId:[0-9]+}/source-confirmations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<SkillImportTaskRespDTO> confirm(@PathVariable @Positive Long jobId,
                                                 @RequestBody @Valid SkillGithubSourceConfirmReqDTO input,
                                                 @RequestHeader("Idempotency-Key") String key) {
        return Results.success(service.confirm(actor(), jobId, input, key));
    }

    /** @return 本人任务的有界候选快照 */
    @GetMapping("/imports/{jobId:[0-9]+}/candidates")
    public Result<SkillGithubCandidatesRespDTO> candidates(@PathVariable @Positive Long jobId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return Results.success(service.candidates(actor(), jobId, page, size));
    }

    /** @return 唯一所选目录进入完整检查 */
    @PostMapping("/imports/{jobId:[0-9]+}/selections")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Result<SkillImportTaskRespDTO> select(@PathVariable @Positive Long jobId,
                                               @RequestBody @Valid SkillGithubSelectionReqDTO input,
                                               @RequestHeader("Idempotency-Key") String key) {
        return Results.success(service.select(actor(), jobId, input, key));
    }

    private Long actor() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.getUserId() == null || context.getApiKeyId() != null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return context.getUserId();
    }
}
