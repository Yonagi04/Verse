package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.common.security.CurrentUser;
import com.yonagi.verse.dto.req.*;
import com.yonagi.verse.dto.resp.*;
import com.yonagi.verse.service.skillhub.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 账号私有收藏子资源；不接受客户端账号或租户归属。 */
@RestController
@RequestMapping("/api/v1/skills/me")
@RequiredArgsConstructor
@Validated
public class SkillFavoriteController {
    private final SkillFavoriteQueryService queries;
    private final SkillFavoriteCommandService commands;

    /** @return 默认夹、全部自定义夹与未筛选计数。 */
    @GetMapping("/favorite-folders")
    public Result<SkillFavoriteFoldersRespDTO> folders(@CurrentUser Long userId) {
        return Results.success(queries.folders(userId));
    }

    /** @return 首次新建 201；同键回放 200。 */
    @PostMapping("/favorite-folders")
    public ResponseEntity<Result<SkillFavoriteFolderReceiptRespDTO>> createFolder(@CurrentUser Long userId,
            @RequestHeader("Idempotency-Key") @Size(max = 36) String key,
            @Valid @RequestBody SkillFavoriteFolderReqDTO input) {
        SkillFavoriteCommandService.FolderCreation result = commands.createFolder(userId, input.name(), key);
        return ResponseEntity.status(result.created() ? 201 : 200).body(Results.success(result.receipt()));
    }

    /** @return 本人自定义夹改名回执。 */
    @PutMapping("/favorite-folders/{folderId}")
    public Result<SkillFavoriteFolderReceiptRespDTO> renameFolder(@CurrentUser Long userId,
            @PathVariable @Positive Long folderId, @RequestHeader("Idempotency-Key") @Size(max = 36) String key,
            @Valid @RequestBody SkillFavoriteFolderReqDTO input) {
        return Results.success(commands.renameFolder(userId, folderId, input.name(), key));
    }

    /** @return 原子迁入默认夹后的删除回执，重复删除仍成功。 */
    @DeleteMapping("/favorite-folders/{folderId}")
    public Result<SkillFavoriteFolderReceiptRespDTO> deleteFolder(@CurrentUser Long userId,
            @PathVariable @Positive Long folderId, @RequestHeader("Idempotency-Key") @Size(max = 36) String key) {
        return Results.success(commands.deleteFolder(userId, folderId, key));
    }

    /** @return 当前范围分页；空 folderId 为全部，0 为默认夹。 */
    @GetMapping("/favorites")
    public Result<SkillFavoritePageRespDTO> page(@CurrentUser Long userId,
            @RequestParam(required = false) @PositiveOrZero Long folderId,
            @RequestParam(required = false) @Size(max = 320) String keyword,
            @RequestParam(defaultValue = "saved") String sort,
            @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
            @RequestParam(defaultValue = "12") int size) {
        return Results.success(queries.page(userId, SkillFavoriteRules.condition(folderId, keyword, sort, page, size)));
    }

    /** @return 当前页最多 24 个 Skill 的去重状态，不含源内容。 */
    @GetMapping("/favorites/statuses")
    public Result<List<SkillFavoriteStatusRespDTO>> statuses(@CurrentUser Long userId,
            @RequestParam List<Long> skillIds) {
        return Results.success(queries.statuses(userId, SkillFavoriteRules.statusIds(skillIds)));
    }

    /** @return 新关系 201，既有或回放 200。 */
    @PostMapping("/favorites")
    public ResponseEntity<Result<SkillFavoriteReceiptRespDTO>> create(@CurrentUser Long userId,
            @RequestHeader("Idempotency-Key") @Size(max = 36) String key,
            @Valid @RequestBody SkillFavoriteCreateReqDTO input) {
        SkillFavoriteCommandService.Creation result = commands.create(userId, input.skillId(), input.folderId(), key);
        return ResponseEntity.status(result.created() ? 201 : 200).body(Results.success(result.receipt()));
    }

    /** @return 移动回执，保持原收藏时间。 */
    @PutMapping("/favorites/{favoriteId}")
    public Result<SkillFavoriteReceiptRespDTO> move(@CurrentUser Long userId,
            @PathVariable @Positive Long favoriteId, @RequestHeader("Idempotency-Key") @Size(max = 36) String key,
            @Valid @RequestBody SkillFavoriteMoveReqDTO input) {
        return Results.success(commands.move(userId, favoriteId, input.folderId(), key));
    }

    /** @return 取消和短时撤销快照；已缺失关系不提供撤销。 */
    @DeleteMapping("/favorites/{favoriteId}")
    public Result<SkillFavoriteReceiptRespDTO> cancel(@CurrentUser Long userId,
            @PathVariable @Positive Long favoriteId, @RequestHeader("Idempotency-Key") @Size(max = 36) String key) {
        return Results.success(commands.cancel(userId, favoriteId, key));
    }

    /** @return 使用原取消 UUID 的恢复结果，不接收客户端快照。 */
    @PostMapping("/favorites/{favoriteId}/restore")
    public Result<SkillFavoriteReceiptRespDTO> restore(@CurrentUser Long userId,
            @PathVariable @Positive Long favoriteId, @RequestHeader("Idempotency-Key") @Size(max = 36) String key) {
        return Results.success(commands.restore(userId, favoriteId, key));
    }
}
