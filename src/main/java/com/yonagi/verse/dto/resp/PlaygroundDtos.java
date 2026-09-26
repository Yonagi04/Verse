package com.yonagi.verse.dto.resp;

import java.util.List;

/** PlayGround 响应对象；业务 ID 以字符串返回，避免浏览器整数精度丢失。 */
public final class PlaygroundDtos {
    private PlaygroundDtos() { }

    public record Status(boolean enabled, int limitRpm, int limitRph) { }
    public record Model(String serviceId, String name, String provider, String description,
                        Long contextWindow) { }
    public record Models(List<Model> items) { }
    public record Prompt(
            /** 提示词的稳定标识。 */ String id,
            /** 卡片标题。 */ String title,
            /** 卡片简述。 */ String description,
            /** 回填输入框的完整内容。 */ String prompt) { }
    public record Prompts(/** 有序的新会话提示词列表。 */ List<Prompt> items) { }
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
