package com.yonagi.verse.common.validation;

import com.yonagi.verse.common.convention.errorcode.BaseErrorCode;
import com.yonagi.verse.common.convention.errorcode.IErrorCode;
import com.yonagi.verse.common.convention.exception.ClientException;

/** 列表统一拒绝无界分页；领域可以保留更严格的上限和原有错误码。 */
public final class PaginationPolicy {
    public static final int MAX_PAGE_SIZE = 100;

    private PaginationPolicy() { }

    public static void validate(Integer page, Integer size) {
        validate(page, size, MAX_PAGE_SIZE, BaseErrorCode.CLIENT_ERROR);
    }

    public static void validate(Integer page, Integer size, int maxSize, IErrorCode errorCode) {
        if (page == null || size == null || page < 1 || size < 1 || size > Math.min(maxSize, MAX_PAGE_SIZE)) {
            throw new ClientException("页码必须大于等于 1，每页数量必须在 1 到 "
                    + Math.min(maxSize, MAX_PAGE_SIZE) + " 之间", errorCode);
        }
    }
}
