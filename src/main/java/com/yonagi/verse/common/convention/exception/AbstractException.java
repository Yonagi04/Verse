package com.yonagi.verse.common.convention.exception;

import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.Optional;


/**
 * @author Yonagi
 */
@Getter
public class AbstractException extends RuntimeException {

    public final String errorCode;

    public final String errorMessage;

    public AbstractException(String message, IErrorCode errorCode, Throwable throwable) {
        super(message, throwable);
        this.errorCode = errorCode.code();
        this.errorMessage = Optional.ofNullable(StringUtils.hasLength(message) ? message : null)
                .orElse(errorCode.message());
    }
}
