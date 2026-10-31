package com.yonagi.verse.controller.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.SkillCommentCreateReqDTO;
import com.yonagi.verse.dto.req.SkillCommentReactionReqDTO;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.skillhub.SkillCommentCommandService;
import com.yonagi.verse.service.skillhub.SkillCommentQueryService;
import com.yonagi.verse.service.skillhub.port.SkillPublicQueryRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Skill详情评论子资源；登录主体来自可信账号上下文，不使用租户角色授权。 */
@RestController
@RequestMapping("/api/v1/skills/{skillId:[0-9]+}/comments")
@RequiredArgsConstructor
@Validated
public class SkillCommentController {
    private final SkillCommentQueryService queries;
    private final SkillCommentCommandService commands;
    private final SkillPublicQueryRateLimiter rateLimiter;

    /** @return 父评论分页，最热默认按自身点赞数排序。 */
    @GetMapping
    public Result<SkillPageRespDTO<SkillCommentRespDTO>> roots(
            @CurrentUser Long actorId, @PathVariable @Positive Long skillId,
            @RequestParam(defaultValue = "hot") String sort,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size, HttpServletRequest request) {
        rateLimiter.check(DigestUtil.sha256Hex(request.getRemoteAddr()));
        return Results.success(queries.roots(actorId, skillId, sort, page, size));
    }

    /** @return 根下有效回复的正序分页，不递归加载整棵回复树。 */
    @GetMapping("/{rootId}/replies")
    public Result<SkillPageRespDTO<SkillCommentRespDTO>> replies(
            @CurrentUser Long actorId, @PathVariable @Positive Long skillId,
            @PathVariable @Positive Long rootId,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size, HttpServletRequest request) {
        rateLimiter.check(DigestUtil.sha256Hex(request.getRemoteAddr()));
        return Results.success(queries.replies(actorId, skillId, rootId, page, size));
    }

    /** @return 首次创建201，同key回放200；回执只含新评论定位ID。 */
    @PostMapping
    public ResponseEntity<Result<SkillCommentCreatedRespDTO>> create(
            @CurrentUser Long actorId, @PathVariable @Positive Long skillId,
            @RequestHeader("Idempotency-Key") @Size(max = 36) String key,
            @Valid @RequestBody SkillCommentCreateReqDTO input) {
        var result = commands.create(actorId, skillId, input, key);
        return ResponseEntity.status(result.created() ? 201 : 200).body(Results.success(result.receipt()));
    }

    /** @return 设置目标反应后的权威计数，不进行非幂等toggle。 */
    @PutMapping("/{commentId}/reaction")
    public Result<SkillCommentReactionRespDTO> react(
            @CurrentUser Long actorId, @PathVariable @Positive Long skillId,
            @PathVariable @Positive Long commentId, @Valid @RequestBody SkillCommentReactionReqDTO input) {
        return Results.success(commands.react(actorId, skillId, commentId, input.reaction()));
    }

    /** @return 本人软删除成功，重复删除同样成功。 */
    @DeleteMapping("/{commentId}")
    public Result<Void> delete(@CurrentUser Long actorId, @PathVariable @Positive Long skillId,
                               @PathVariable @Positive Long commentId) {
        commands.delete(actorId, skillId, commentId);
        return Results.success();
    }
}
