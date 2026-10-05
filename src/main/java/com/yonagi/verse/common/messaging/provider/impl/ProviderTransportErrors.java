package com.yonagi.verse.common.messaging.provider.impl;

import com.yonagi.verse.dto.resp.MessageSubmissionRespDTO;
import jakarta.mail.MessagingException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.*;

/** 只确认连接建立前失败；读取超时、连接重置和 HTTP 5xx 都不能证明未发送。 */
public final class ProviderTransportErrors {
    private ProviderTransportErrors() { }
    public static MessageSubmissionRespDTO result(Throwable failure) {
        var pending = new ArrayDeque<Throwable>(); pending.add(failure);
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (!pending.isEmpty() && visited.size() < 32) {
            Throwable error = pending.remove(); if (!visited.add(error)) continue;
            if (error instanceof ConnectException || error instanceof UnknownHostException) return MessageSubmissionRespDTO.retryable("CONNECT_FAILED");
            if (error.getCause() != null) pending.add(error.getCause());
            if (error instanceof MessagingException mail && mail.getNextException() != null) pending.add(mail.getNextException());
        }
        return MessageSubmissionRespDTO.unknown("TRANSPORT_ERROR");
    }
}
