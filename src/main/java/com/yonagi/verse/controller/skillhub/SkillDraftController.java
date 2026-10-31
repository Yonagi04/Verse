package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.convention.exception.ClientException;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.enums.SkillErrorCodeEnum;
import com.yonagi.verse.common.security.UserContext;
import com.yonagi.verse.common.security.UserContextHolder;
import com.yonagi.verse.dto.req.SkillDraftCreateReqDTO;
import com.yonagi.verse.dto.req.SkillDraftSaveReqDTO;
import com.yonagi.verse.dto.req.SkillDraftSubmitReqDTO;
import com.yonagi.verse.dto.req.SkillUnpublishReqDTO;
import com.yonagi.verse.dto.req.SkillImportCreateReqDTO;
import com.yonagi.verse.dto.resp.SkillImportTaskRespDTO;
import com.yonagi.verse.dto.resp.SkillMyPublicationRespDTO;
import com.yonagi.verse.dto.resp.SkillPageRespDTO;
import com.yonagi.verse.dto.resp.SkillDraftRespDTO;
import com.yonagi.verse.dto.resp.SkillPublicationRespDTO;
import com.yonagi.verse.service.skillhub.SkillDraftService;
import com.yonagi.verse.service.skillhub.SkillPublicationService;
import com.yonagi.verse.service.skillhub.SkillLifecycleService;
import com.yonagi.verse.service.skillhub.SkillImportTaskService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 个人发布写入口，可信账号不依赖当前租户。 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
@Validated
public class SkillDraftController {
    private final SkillDraftService draftService;
    private final SkillPublicationService publicationService;
    private final SkillLifecycleService lifecycleService;
    private final SkillImportTaskService importTaskService;
    private final SkillZipUploadReceiver uploadReceiver;

    /**
     * @param submissionId 本人提交标识
     * @return 持久化发布回执和当前可见状态
     */
    @GetMapping("/submissions/{submissionId:[0-9]+}")
    public Result<SkillPublicationRespDTO> submission(@PathVariable @Positive Long submissionId) {
        return Results.success(publicationService.get(actor(), submissionId));
    }

    /**
     * @param draftId 本人草稿
     * @param input 当前修订
     * @param key 创建导入UUID
     * @return 可恢复的等待上传任务
     */
    @PostMapping("/drafts/{draftId:[0-9]+}/imports")
    public Result<SkillImportTaskRespDTO> createImport(@PathVariable @Positive Long draftId,
                                                      @Valid @RequestBody SkillImportCreateReqDTO input,
                                                      @RequestHeader("Idempotency-Key") String key) {
        return Results.success(importTaskService.create(actor(), draftId, input, key));
    }

    /**
     * @param jobId 本人等待上传任务
     * @param request 原始ZIP体，不经过multipart暂存
     * @param response 异步接收完成后返回202和持久化任务
     * @throws IOException 请求输入流不可用
     */
    @PutMapping(value = "/imports/{jobId:[0-9]+}/archive", consumes = "application/zip")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void upload(@PathVariable @Positive Long jobId, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        uploadReceiver.receive(actor(), jobId, request, response);
    }

    /**
     * @param jobId 本人导入任务
     * @return 持久化检查状态及安全拒绝原因
     */
    @GetMapping("/imports/{jobId:[0-9]+}")
    public Result<SkillImportTaskRespDTO> importStatus(@PathVariable @Positive Long jobId) {
        return Results.success(importTaskService.get(actor(), jobId));
    }

    /**
     * @param jobId 本人导入任务
     * @return 取消完成，执行中的旧代次无法提交
     */
    @DeleteMapping("/imports/{jobId:[0-9]+}")
    public Result<Void> cancelImport(@PathVariable @Positive Long jobId) {
        importTaskService.cancel(actor(), jobId);
        return Results.success();
    }

    /**
     * @param page 本人资源页码
     * @return 包含未提交草稿的管理列表
     */
    @GetMapping("/me/publications")
    public Result<SkillPageRespDTO<SkillMyPublicationRespDTO>> mine(@RequestParam(defaultValue = "1") int page) {
        return Results.success(lifecycleService.mine(actor(), page));
    }

    /**
     * @param skillId 本人Skill
     * @param input 下架确认及修订
     * @param key 重试复用的UUID
     * @return 新修订；原内容不再分发
     */
    @PostMapping("/{skillId:[0-9]+}/unpublish")
    public Result<Long> unpublish(@PathVariable @Positive Long skillId,
                                  @Valid @RequestBody SkillUnpublishReqDTO input,
                                  @RequestHeader("Idempotency-Key") String key) {
        return Results.success(lifecycleService.unpublish(actor(), skillId, input, key));
    }

    /**
     * @param input 固定slug
     * @param key 重试复用的UUID
     * @return 可恢复草稿
     */
    @PostMapping("/drafts")
    public Result<SkillDraftRespDTO> create(@Valid @RequestBody SkillDraftCreateReqDTO input,
                                           @RequestHeader("Idempotency-Key") String key) {
        return Results.success(draftService.create(actor(), input, key));
    }

    /**
     * @param draftId 本人草稿
     * @return 服务端修订与检查结果
     */
    @GetMapping("/drafts/{draftId:[0-9]+}")
    public Result<SkillDraftRespDTO> get(@PathVariable @Positive Long draftId) {
        return Results.success(draftService.get(actor(), draftId));
    }

    /**
     * @param draftId 本人草稿
     * @param input 完整表单和修订
     * @return 保存后的修订
     */
    @PutMapping("/drafts/{draftId:[0-9]+}")
    public Result<SkillDraftRespDTO> save(@PathVariable @Positive Long draftId,
                                         @Valid @RequestBody SkillDraftSaveReqDTO input) {
        return Results.success(draftService.save(actor(), draftId, input));
    }

    /**
     * @param draftId 本人草稿
     * @param revision 服务端修订
     * @return 取消完成
     */
    @DeleteMapping("/drafts/{draftId:[0-9]+}")
    public Result<Void> abandon(@PathVariable @Positive Long draftId,
                                @RequestParam @PositiveOrZero Long revision) {
        draftService.abandon(actor(), draftId, revision);
        return Results.success();
    }

    /**
     * @param draftId 本人READY草稿
     * @param input 确认修订
     * @param key UUID
     * @return 原子发布回执
     */
    @PostMapping("/drafts/{draftId:[0-9]+}/submissions")
    public Result<SkillPublicationRespDTO> submit(@PathVariable @Positive Long draftId,
                                                 @Valid @RequestBody SkillDraftSubmitReqDTO input,
                                                 @RequestHeader("Idempotency-Key") String key) {
        return Results.success(publicationService.submit(actor(), draftId, input, key));
    }

    private Long actor() {
        UserContext context = UserContextHolder.get();
        if (context == null || context.getUserId() == null || context.getApiKeyId() != null) {
            throw new ClientException(SkillErrorCodeEnum.NOT_ACCESSIBLE);
        }
        return context.getUserId();
    }
}
