package com.yonagi.verse.async.event;

import com.yonagi.verse.async.api.DomainEvent;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 页消息仅定位业务事实，金额与权限始终重新读取主库。 */
@Getter
@Setter
public class CostReconciliationTaskEvent extends DomainEvent {
    public static final String TYPE = "COST_RECONCILIATION_TASK";
    /** CLASSIFY 只接受请求身份，EXPIRE 只接受案件身份及版本。 */
    public enum Operation {
        /** 对原请求做首次核账分类。 */
        CLASSIFY_REQUESTS,
        /** 按原案件版本处理 AUTO 超时。 */
        EXPIRE_AUTOMATIC_CASES
    }
    /** 消息协议版本。 */
    private Integer schemaVersion;
    /** 稳定页面标识。 */
    private String taskId;
    /** 所属运行。 */
    private Long runId;
    /** 发现时启用代次。 */
    private Long generation;
    /** 页面唯一租户。 */
    private Long tenantId;
    /** 本页业务操作。 */
    private Operation operation;
    /** 分类请求的冻结顺序。 */
    private List<String> requestIds;
    /** 超时案件的冻结顺序与版本。 */
    private List<CaseVersion> cases;
    /** 规范化载荷摘要。 */
    private String payloadHash;

    public record CaseVersion(
            /** 原案件身份。 */ Long caseId,
            /** 发现时的案件版本。 */ Long version) { }

    @Override
    public String eventType() { return TYPE; }
}
