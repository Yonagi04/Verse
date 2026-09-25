package com.yonagi.verse.dto.resp;

import java.util.List;

/** PlayGround 响应对象；业务 ID 以字符串返回，避免浏览器整数精度丢失。 */
public final class PlaygroundDtos {
    private PlaygroundDtos() { }

    public record Status(boolean enabled, int limitRpm, int limitRph) { }
    public record Model(String serviceId, String name, String provider, String description,
                        Long contextWindow) { }
    public record Models(List<Model> items) { }
    public record Summary(String sessionId, String title, String serviceId, String modelName,
                          int turnCount, String createdAt, String updatedAt) { }
    public record Sessions(List<Summary> sessions, long total, long totalPages,
                           int page, int pageSize) { }
    public record Turn(String turnId, String prompt, String reply, String status,
                       String requestId, String createdAt, String finishedAt) { }
    public record Detail(String sessionId, String title, String serviceId, String modelName,
                         boolean modelAvailable, int turnCount, String createdAt,
                         String updatedAt, List<Turn> turns) { }
}
