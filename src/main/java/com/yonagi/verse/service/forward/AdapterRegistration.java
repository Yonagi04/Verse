package com.yonagi.verse.service.forward;

import com.yonagi.verse.common.enums.ModelOperation;
import com.yonagi.verse.common.enums.UpstreamProtocol;

/** 一个适配器明确声明支持的客户端操作及上游协议。 */
public interface AdapterRegistration {
    ModelOperation operation();
    UpstreamProtocol protocol();
    /** 原生协议限定供应商；共享协议返回空值。 */
    String provider();
}
