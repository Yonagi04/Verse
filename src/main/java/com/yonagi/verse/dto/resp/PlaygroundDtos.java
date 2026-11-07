package com.yonagi.verse.dto.resp;

import com.yonagi.verse.dto.playground.WorkbenchTypes.ErrorDataVO;

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

    /** 一期会话的 SSE 事件体，供前端更新当前轮次。 */
    public sealed interface TurnEventVO permits AcceptedVO, DeltaVO, CompletedVO, FailedVO { }

    /** 会话已接受生成请求。 */
    public record AcceptedVO(
            /** 当前会话标识。 */ String sessionId,
            /** 本次生成的轮次标识。 */ String turnId,
            /** 关联模型调用的请求标识。 */ String requestId,
            /** 接受请求后的轮次状态。 */ String status) implements TurnEventVO { }

    /** 本次生成的文本增量。 */
    public record DeltaVO(
            /** 增量所属轮次。 */ String turnId,
            /** 从 1 开始的轮次内增量序号。 */ Integer seq,
            /** 本次追加的文本。 */ String text) implements TurnEventVO { }

    /** 本次生成已结束。 */
    public record CompletedVO(
            /** 已完成的轮次。 */ String turnId,
            /** 生成完成后的轮次状态。 */ String status,
            /** 完整回复。 */ String reply) implements TurnEventVO { }

    /** 本次生成失败，可附带脱敏预算信息。 */
    public record FailedVO(
            /** 发生失败的轮次。 */ String turnId,
            /** 生成失败后的轮次状态。 */ String status,
            /** 稳定业务错误码。 */ String code,
            /** 供前端展示的错误文案。 */ String message,
            /** 命中预算时可展示的恢复信息。 */ ErrorDataVO data) implements TurnEventVO { }
}
