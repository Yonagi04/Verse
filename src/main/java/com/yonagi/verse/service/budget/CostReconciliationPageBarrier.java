package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.dao.entity.CostReconciliationTaskDO;
import com.yonagi.verse.dao.mapper.CostReconciliationTaskMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 逐笔短事务先共享页屏障，再申请业务锁；终态独占屏障等待已开始的业务排空。 */
@Component
@RequiredArgsConstructor
public class CostReconciliationPageBarrier {
    private final CostReconciliationTaskMapper tasks;
    private final CostReconciliationScanMapper scans;

    @Transactional(propagation = Propagation.MANDATORY, rollbackFor = Exception.class)
    public boolean admit(CostReconciliationTaskEvent event) {
        CostReconciliationTaskDO page = tasks.share(event.getTaskId());
        verify(page, event);
        return "PENDING".equals(page.getOutcome()) && scans.databaseTime().isBefore(page.getExecutionDeadlineAt());
    }

    public static void verify(CostReconciliationTaskDO page, CostReconciliationTaskEvent event) {
        if (page == null || !Objects.equals(page.getTaskId(), event.getTaskId())
                || !Objects.equals(page.getScanId(), event.getRunId())
                || !Objects.equals(page.getTenantId(), event.getTenantId())
                || !Objects.equals(page.getOperation(), event.getOperation().name())
                || !Objects.equals(page.getPayloadHash(), event.getPayloadHash())) {
            throw new IllegalArgumentException("reconciliation page identity mismatch");
        }
    }
}
