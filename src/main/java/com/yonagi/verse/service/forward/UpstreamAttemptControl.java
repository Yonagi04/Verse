package com.yonagi.verse.service.forward;

/** 单次物理尝试的发送与取消边界；实现不得在原子决策中等待外部依赖。 */
public interface UpstreamAttemptControl {
    void checkActive();
    void startSending();
    UpstreamExecutionOutcome cancel();
    void complete(UpstreamExecutionOutcome outcome);
}
