package com.yonagi.verse.common.convention.exception;

import com.yonagi.verse.common.enums.MessagingErrorCode;

/** 携带副作用边界；业务只在确定没有受理时恢复验证码，不依赖异常类型猜测。 */
public class MessageSendException extends ServerException {
    private final boolean mayHaveBeenSubmitted;

    public MessageSendException(MessagingErrorCode code, boolean mayHaveBeenSubmitted) {
        super(code);
        this.mayHaveBeenSubmitted = mayHaveBeenSubmitted;
    }

    public boolean mayHaveBeenSubmitted() { return mayHaveBeenSubmitted; }
}
