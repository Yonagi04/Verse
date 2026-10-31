package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.dto.req.SkillSearchReqDTO;
import com.yonagi.verse.dto.resp.SkillCardRespDTO;
import com.yonagi.verse.dto.resp.SkillDetailRespDTO;
import com.yonagi.verse.dto.resp.SkillOptionsRespDTO;
import com.yonagi.verse.dto.resp.SkillPageRespDTO;
import com.yonagi.verse.service.skillhub.SkillQueryService;
import com.yonagi.verse.service.skillhub.port.SkillPublicQueryRateLimiter;
import cn.hutool.crypto.digest.DigestUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 全站公开读取，不将tenantId作为资源身份或权限。 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
@Validated
public class SkillQueryController {
    private final SkillQueryService queryService;
    private final SkillPublicQueryRateLimiter rateLimiter;

    /**
     * @param query 有界公开搜索，不接收所有权字段
     * @return 可用内容稳定分页，空结果返回空items
     */
    @GetMapping
    public Result<SkillPageRespDTO<SkillCardRespDTO>> search(@Valid @ModelAttribute SkillSearchReqDTO query,
                                                           HttpServletRequest request) {
        rateLimiter.check(DigestUtil.sha256Hex(request.getRemoteAddr()));
        return Results.success(queryService.search(query));
    }

    /**
     * @return 平台词表、权威上限及当前运行能力
     */
    @GetMapping("/options")
    public Result<SkillOptionsRespDTO> options(HttpServletRequest request) {
        rateLimiter.check(DigestUtil.sha256Hex(request.getRemoteAddr()));
        return Results.success(queryService.options());
    }

    /**
     * @param publisher 公开作者标识
     * @param slug 作者下的Skill标识
     * @return 曾公开且账号可用的稳定标识
     */
    @GetMapping("/lookup")
    public Result<String> lookup(@RequestParam @Size(max = 32) String publisher,
                                 @RequestParam @Size(max = 64) String slug, HttpServletRequest request) {
        rateLimiter.check(DigestUtil.sha256Hex(request.getRemoteAddr()));
        return Results.success(queryService.lookup(publisher, slug));
    }

    /**
     * @param skillId 公开稳定标识
     * @return 当前快照或有限不可用占位，不能读取私人草稿
     */
    @GetMapping("/{skillId:[0-9]+}")
    public Result<SkillDetailRespDTO> detail(@PathVariable @Positive Long skillId, HttpServletRequest request) {
        rateLimiter.check(DigestUtil.sha256Hex(request.getRemoteAddr()));
        return Results.success(queryService.detail(skillId));
    }
}
