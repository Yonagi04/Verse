package com.yonagi.verse.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 用户费用提醒的外发开关，沿用既有发布配置键。 */
@Data @Component
@ConfigurationProperties("verse.personal-usage")
public class UserCostAlertDeliveryProperties {
    /** 全部消息Worker升级后才允许用户费用预警外发。 */
    private boolean deliveryEnabled;
}
