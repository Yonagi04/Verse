package com.yonagi.verse.service.budget;

import com.yonagi.verse.common.convention.exception.ServerException;
import com.yonagi.verse.common.enums.CostBudgetErrorCodeEnum;

public class CostBudgetUnavailableException extends ServerException {
    public CostBudgetUnavailableException() { super(CostBudgetErrorCodeEnum.UNAVAILABLE); }
}
