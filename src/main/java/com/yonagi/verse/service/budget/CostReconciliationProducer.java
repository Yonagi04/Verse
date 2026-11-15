package com.yonagi.verse.service.budget;

import com.alibaba.fastjson2.JSON;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.async.event.CostReconciliationTaskEvent.Operation;
import com.yonagi.verse.dao.entity.CostBudgetInvocationDO;
import com.yonagi.verse.dao.entity.CostReconciliationCaseDO;
import com.yonagi.verse.dao.entity.CostReconciliationScanDO;
import com.yonagi.verse.dao.mapper.CostBudgetInvocationMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationCaseMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** 合法触发中的只读候选分页；租户轮转，每种操作一页后让给下一租户。 */
@Service
@RequiredArgsConstructor
public class CostReconciliationProducer {
    private final CostReconciliationScanCoordinator coordinator;
    private final CostReconciliationScanMapper scans;
    private final CostReconciliationTaskMapper tasks;
    private final CostBudgetInvocationMapper invocations;
    private final CostReconciliationCaseMapper cases;

    public void discover(String trigger, boolean daily) {
        String token = UUID.randomUUID().toString();
        var run = coordinator.begin(trigger, token, daily);
        if (run == null) {
            return;
        }
        boolean complete = false;
        try {
            long afterTenant = 0;
            boolean producedInPass = false;
            while (!Thread.currentThread().isInterrupted() && scans.databaseTime().isBefore(run.getDiscoveryDeadlineAt())) {
                Long requests = invocations.nextCandidateTenant(run.getGeneration(), afterTenant, run.getCutoffAt());
                Long automatic = cases.nextExpiredTenant(afterTenant, run.getCutoffAt());
                Long tenant;
                if (requests == null) {
                    tenant = automatic;
                } else if (automatic == null) {
                    tenant = requests;
                } else {
                    tenant = Math.min(requests, automatic);
                }
                if (tenant == null) {
                    if (!producedInPass) {
                        complete = true;
                        break;
                    }
                    afterTenant = 0;
                    producedInPass = false;
                    continue;
                }
                if (tenant <= afterTenant) {
                    throw new IllegalStateException("reconciliation tenant cursor did not advance");
                }
                boolean added = produceRequests(run, token, tenant);
                added |= produceExpiries(run, token, tenant);
                producedInPass |= added;
                afterTenant = tenant;
                // 其他节点可在每日触发冻结启动生产者；旧 token 不得继续发现。
                var current = scans.selectById(run.getId());
                if (!"OPEN".equals(current.getState()) || !token.equals(current.getProducerToken())
                        || !scans.databaseTime().isBefore(current.getProducerLeaseUntil())) {
                    break;
                }
            }
        } finally {
            coordinator.close(run.getId(), token, complete);
        }
    }

    private boolean produceRequests(CostReconciliationScanDO run, String token, Long tenant) {
        var cursor = tasks.lastPage(run.getId(), tenant, Operation.CLASSIFY_REQUESTS.name());
        List<CostBudgetInvocationDO> candidates = invocations.candidatePage(run.getGeneration(), tenant, run.getCutoffAt(), cursor);
        if (candidates.isEmpty()) {
            return false;
        }
        var event = page(run, tenant, Operation.CLASSIFY_REQUESTS);
        int count = candidates.size();
        do {
            event.setRequestIds(candidates.subList(0, count).stream().map(CostBudgetInvocationDO::getRequestId).toList());
            event.setPayloadHash(CostReconciliationTaskProtocol.hash(event));
            if (fits(event)) {
                break;
            }
            count /= 2;
        } while (count > 0);
        CostReconciliationTaskProtocol.validate(event);
        var last = candidates.get(count - 1);
        boolean appended = coordinator.append(run.getId(), token, event, last.getLeaseUntil(), last.getId(), run.getTaskCount());
        if (appended) {
            run.setTaskCount(Math.addExact(run.getTaskCount(), 1));
        }
        return appended;
    }

    private boolean produceExpiries(CostReconciliationScanDO run, String token, Long tenant) {
        var cursor = tasks.lastPage(run.getId(), tenant, Operation.EXPIRE_AUTOMATIC_CASES.name());
        List<CostReconciliationCaseDO> candidates = cases.expiredPage(tenant, run.getCutoffAt(), cursor);
        if (candidates.isEmpty()) {
            return false;
        }
        var event = page(run, tenant, Operation.EXPIRE_AUTOMATIC_CASES);
        int count = candidates.size();
        do {
            event.setCases(candidates.subList(0, count).stream()
                    .map(row -> new CostReconciliationTaskEvent.CaseVersion(row.getId(), row.getVersion())).toList());
            event.setPayloadHash(CostReconciliationTaskProtocol.hash(event));
            if (fits(event)) {
                break;
            }
            count /= 2;
        } while (count > 0);
        CostReconciliationTaskProtocol.validate(event);
        var last = candidates.get(count - 1);
        boolean appended = coordinator.append(run.getId(), token, event, last.getAutomaticDeadlineAt(), last.getId(), run.getTaskCount());
        if (appended) {
            run.setTaskCount(Math.addExact(run.getTaskCount(), 1));
        }
        return appended;
    }

    private CostReconciliationTaskEvent page(CostReconciliationScanDO run, Long tenant, Operation operation) {
        var event = new CostReconciliationTaskEvent();
        event.setSchemaVersion(1);
        event.setTaskId("crt-" + run.getId() + "-" + run.getTaskCount());
        event.setEventId(event.getTaskId());
        event.setKey(String.valueOf(tenant));
        event.setOccurredAt(run.getCutoffAt().atZone(BudgetPeriodResolver.ZONE).toInstant().toEpochMilli());
        event.setRunId(run.getId());
        event.setGeneration(run.getGeneration());
        event.setTenantId(tenant);
        event.setOperation(operation);
        return event;
    }

    private boolean fits(CostReconciliationTaskEvent event) {
        return JSON.toJSONString(event).getBytes(StandardCharsets.UTF_8).length <= CostReconciliationTaskProtocol.MAX_BYTES;
    }
}
