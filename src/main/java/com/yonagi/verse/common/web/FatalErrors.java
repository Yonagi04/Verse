package com.yonagi.verse.common.web;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 在普通错误映射前排除不可恢复错误；按对象身份防止不规范 cause 链循环。 */
public final class FatalErrors {
    private FatalErrors() {
    }

    /**
     * 重抛原始致命错误实例，普通异常交还调用方处理。
     *
     * @param error 异常处理边界收到的异常及其原因链
     * @throws VirtualMachineError JVM 资源或执行环境不可恢复
     * @throws ThreadDeath 线程终止信号不能包装成业务结果
     * @throws LinkageError 类加载或链接环境不可用
     */
    public static void rethrowIfFatal(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = error; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (cause instanceof ThreadDeath fatal) {
                throw fatal;
            }
            if (cause instanceof LinkageError fatal) {
                throw fatal;
            }
        }
    }
}
