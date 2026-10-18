package com.yonagi.verse.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 用户模型用量处理默认运行，保留运维暂停采集或工作项处理的能力。 */
@Data
@Component
@ConfigurationProperties("verse.personal-usage")
public class UserModelUsageProperties {
    /** 消费事务生产用户模型用量工作项。 */
    private boolean projectionEnabled = true;
    /** 后台处理工作项。 */
    private boolean workerEnabled = true;
}
