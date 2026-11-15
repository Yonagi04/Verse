package com.yonagi.verse.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 有界资源与已预检的 Broker 延迟映射；期限只在运行创建时快照。 */
@Getter
@Setter
@Validated
@Component
@ConfigurationProperties("verse.llm.cost-reconciliation")
public class CostReconciliationMqProperties {
    @NotBlank
    private String taskTopic = "verse-cost-reconciliation-task";
    @NotBlank
    private String controlTopic = "verse-cost-reconciliation-control";
    @Min(1) @Max(600)
    private int discoverySeconds = 600;
    @Min(600) @Max(86400)
    private int executionSeconds = 86400;
    @Min(86401) @Max(172800)
    private int cleanupSeconds = 172800;
    @Min(1) @Max(18)
    private int discoveryDelayLevel = 14;
    @Min(1) @Max(18)
    private int checkDelayLevel = 18;
    private boolean brokerPreflightVerified;

    /** 发现前核对由部署方确认的延迟映射，不通过配置默认值宣称 Broker 已验证。 */
    public void requireBrokerPreflight() {
        if (!brokerPreflightVerified || taskTopic.equals(controlTopic) || executionSeconds <= discoverySeconds
                || cleanupSeconds <= executionSeconds) {
            throw new IllegalStateException("reconciliation Broker preflight or deadline configuration invalid");
        }
    }
}
