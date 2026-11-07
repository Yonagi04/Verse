package com.yonagi.verse.controller.skillhub;

import com.yonagi.verse.service.skillhub.SkillDownloadPayload;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 在授权下载回调中同步发送 ZIP，传输完成前由用例持有许可。 */
final class SkillZipDownloadResponse {
    private static final String ZIP_CONTENT_TYPE = "application/zip";

    private SkillZipDownloadResponse() {
    }

    static void write(HttpServletResponse response, SkillDownloadPayload payload) {
        byte[] bytes = payload.bytes();
        response.setContentType(ZIP_CONTENT_TYPE);
        response.setContentLengthLong(bytes.length);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(payload.filename(), StandardCharsets.UTF_8).build().toString());
        try {
            response.getOutputStream().write(bytes);
            response.flushBuffer();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
