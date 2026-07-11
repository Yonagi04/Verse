package com.yonagi.verse.common.convention.exception;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.errorcode.IErrorCode;

import java.util.Optional;

/**
 * @author Yonagi
 */
public class ServerException extends AbstractException {
    public ServerException(String message, Throwable throwable, IErrorCode errorCode) {
        super(Optional.ofNullable(message).orElse(errorCode.message()), errorCode, throwable);
    }

    public ServerException(IErrorCode errorCode) {
        this(null, null, errorCode);
    }

    public ServerException(String message) {
        this(message, null, BaseErrorCode.SERVICE_ERROR);
    }

    public ServerException(String message, IErrorCode errorCode) {
        this(message, null, errorCode);
    }

    @Override
    public String toString() {
        return "ServerException{" +
                "code='" + errorCode + "'," +
                "message='" + errorMessage + "'" +
                '}';
    }
}
