package com.yonagi.verse.controller;

import com.yonagi.verse.service.reporting.UsageExportTarget;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 工作簿第一次写入时才打开 Servlet 输出，以便导出前置失败仍可返回 JSON。 */
final class UsageExportResponse extends OutputStream implements UsageExportTarget {
    private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final HttpServletResponse response;
    private String filename;
    private OutputStream output;
    private HttpHeaders originalHeaders;
    private int originalStatus;

    UsageExportResponse(HttpServletResponse response) {
        this.response = response;
    }

    @Override
    public void prepare(String filename) {
        this.filename = filename;
    }

    @Override
    public OutputStream outputStream() {
        return this;
    }

    @Override
    public void write(int value) throws IOException {
        openOutput().write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        openOutput().write(bytes, offset, length);
    }

    @Override
    public void flush() throws IOException {
        if (output != null) {
            output.flush();
        }
    }

    private OutputStream openOutput() throws IOException {
        if (output != null) {
            return output;
        }
        if (filename == null) {
            throw new IOException("工作簿尚未通过导出校验");
        }
        output = response.getOutputStream();
        originalStatus = response.getStatus();
        originalHeaders = new HttpHeaders();
        for (String name : response.getHeaderNames()) {
            originalHeaders.put(name, List.copyOf(response.getHeaders(name)));
        }
        String originalContentType = response.getContentType();
        if (originalContentType != null) {
            originalHeaders.set(HttpHeaders.CONTENT_TYPE, originalContentType);
        }
        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8).build().toString());
        return output;
    }

    void discardUncommittedFile() {
        if (originalHeaders == null) {
            return;
        }
        // Servlet 没有单独移除响应头的 API。重置文件输出后还原此前的 CORS 等响应头。
        response.reset();
        response.setStatus(originalStatus);
        originalHeaders.forEach((name, values) -> values.forEach(value -> response.addHeader(name, value)));
    }
}
