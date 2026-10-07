package com.yonagi.verse.service.forward;

/** 多部分输入和二进制输出的有类型媒体适配器。 */
public interface MediaOperationAdapter {
    /** 普通 ClientException 只表示发送前校验失败；发送后的失败必须携带 UpstreamFailureException 执行证据。 */
    AdapterExchange.Result invoke(ForwardContext context, AdapterExchange.Request request);
}
