package com.yonagi.verse.service.external;

import java.time.Duration;

/** 单次外部认证请求的 Cookie 输出，由 Web 层在用例发放或清理时同步处理。 */
@FunctionalInterface
public interface ExternalAuthCookieOutput {
    /**
     * 在调用点写出一条 Cookie，保留同一请求中多条发放和清理指令的顺序。
     * @param name Cookie 名称，包含流程前缀
     * @param value 浏览器证明，清理时为空
     * @param ttl 有效期，清理时为零
     */
    void write(String name, String value, Duration ttl);
}
