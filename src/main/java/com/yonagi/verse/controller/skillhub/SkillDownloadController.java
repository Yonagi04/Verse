package com.yonagi.verse.controller.skillhub;

import cn.hutool.crypto.digest.DigestUtil;
import com.yonagi.verse.common.convention.result.Result;
import com.yonagi.verse.common.convention.result.Results;
import com.yonagi.verse.dto.resp.SkillDownloadGrantRespDTO;
import com.yonagi.verse.service.skillhub.SkillDownloadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 已登录账号受控下载；ZIP响应是Result契约例外，提交响应前的失败保持JSON错误。 */
@RestController
@RequestMapping("/api/v1/skills")
@RequiredArgsConstructor
@Validated
public class SkillDownloadController {
    private final SkillDownloadService downloadService;
    private final SkillDownloadSubjectResolver subjectResolver;
    private final SkillOriginGuard originGuard;

    /**
     * @param skillId 公开Skill
     * @param versionId 用户当前看到的固定版本
     * @param request 当前请求
     * @param response 禁止缓存短效下载能力
     * @return 60秒受控下载能力，响应丢失时可重新领取
     */
    @PostMapping(value = "/{skillId:[0-9]+}/versions/{versionId:[0-9]+}/download-grants",
            consumes = "application/json")
    public Result<SkillDownloadGrantRespDTO> issue(@PathVariable @Positive Long skillId,
                                                 @PathVariable @Positive Long versionId,
                                                 HttpServletRequest request, HttpServletResponse response) {
        originGuard.requireTrusted(request);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        return Results.success(downloadService.issue(skillId, versionId, subjectResolver.resolve(),
                DigestUtil.sha256Hex(request.getRemoteAddr())));
    }

    /**
     * @param token 不得记录到日志的短效能力
     * @param request 当前连接
     * @param response 最终授权后的ZIP字节；传输开始后故障只中断连接
     */
    @GetMapping("/downloads/{token:[a-f0-9]{64}}")
    public void download(@PathVariable String token, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        downloadService.download(token, subjectResolver.resolve(),
                DigestUtil.sha256Hex(request.getRemoteAddr()), payload -> {
                    response.setContentType("application/zip");
                    response.setContentLengthLong(payload.bytes().length);
                    response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(payload.filename(), StandardCharsets.UTF_8).build().toString());
                    try {
                        response.getOutputStream().write(payload.bytes());
                        response.flushBuffer();
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                });
    }
}
