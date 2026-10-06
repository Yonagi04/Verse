package com.yonagi.verse.service.playground;

import com.yonagi.verse.common.cache.*;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/** 只缓存预设列表并规范化关键词。 */
@Component
public class WorkbenchListCacheBehavior implements QueryCacheBehavior {
    @Override public boolean supports(Object[] args) { return "PRESET".equals(args[2]); }
    @Override public List<Object> parameters(Object[] args) {
        List<Object> parameters = QueryCacheBehavior.super.parameters(args);
        if (args[3] instanceof String keyword) parameters.set(3, keyword.isBlank() ? null : keyword.trim());
        return parameters;
    }
}
