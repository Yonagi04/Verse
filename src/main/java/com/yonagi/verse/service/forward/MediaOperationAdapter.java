package com.yonagi.verse.service.forward;

/** 多部分输入和二进制输出的有类型媒体适配器。 */
public interface MediaOperationAdapter {
    AdapterExchange.Result invoke(ForwardContext context, AdapterExchange.Request request);
}
