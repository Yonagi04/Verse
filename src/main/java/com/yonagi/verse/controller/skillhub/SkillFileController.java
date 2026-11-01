package com.yonagi.verse.controller.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.dto.resp.SkillFileContentRespDTO;
import com.yonagi.verse.dto.resp.SkillFilesRespDTO;
import com.yonagi.verse.service.skillhub.SkillFileQueryService;
import com.yonagi.verse.service.skillhub.port.SkillPublicQueryRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 登录后读取当前公开版本的文件，不开放任意对象或私人草稿。 */
@RestController
@RequestMapping("/api/v1/skills/{skillId:[0-9]+}/versions/{versionId:[0-9]+}/files")
@RequiredArgsConstructor
@Validated
public class SkillFileController {
    private final SkillFileQueryService files;
    private final SkillPublicQueryRateLimiter rateLimiter;

    /** @return 与详情固定版本绑定的有界清单，浏览器不得持久缓存 */
    @GetMapping
    public Result<SkillFilesRespDTO> files(@PathVariable @Positive Long skillId,
                                          @PathVariable @Positive Long versionId,
                                          HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        rateLimiter.check(DigestUtil.sha256Hex(request.getRemoteAddr()));
        return Results.success(files.files(skillId, versionId));
    }

    /** @return 只读文本或不支持/超限元数据；不支持类型不返回正文 */
    @GetMapping("/content")
    public Result<SkillFileContentRespDTO> content(@PathVariable @Positive Long skillId,
                                                  @PathVariable @Positive Long versionId,
                                                  @RequestParam @NotBlank @Size(max = 512) String path,
                                                  HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        rateLimiter.check(DigestUtil.sha256Hex(request.getRemoteAddr()));
        return Results.success(files.content(skillId, versionId, path));
    }
}
