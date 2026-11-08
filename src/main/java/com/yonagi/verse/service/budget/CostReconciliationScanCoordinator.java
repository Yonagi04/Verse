package com.yonagi.verse.service.budget;

import com.yonagi.verse.dao.entity.CostReconciliationScanDO;
import com.yonagi.verse.dao.entity.CostReconciliationWorkDO;
import com.yonagi.verse.dao.mapper.CostReconciliationScanMapper;
import com.yonagi.verse.dao.mapper.CostReconciliationWorkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 扫描锁从不跨越账本调用、执行确认或远程 IO。 */
@Service
@RequiredArgsConstructor
public class CostReconciliationScanCoordinator {
    static final int BATCH_SIZE = 100;
    private static final int DISCOVERY_WINDOW_SECONDS = 30;
    private static final int CLAIM_SECONDS = 60;
    private final CostReconciliationScanMapper scans;
    private final CostReconciliationWorkMapper work;

    public List<CostReconciliationWorkDO> expiredAutomatic() {
        Long generation = scans.activationGeneration();
        return generation == null || generation == 0 ? List.of() : work.expiredAutomatic(generation, scans.databaseTime());
    }

    /** 分类遇到损坏数据或持续依赖故障时留待下轮，防止该行永久阻塞封存。 */
    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public void deferClassificationFailure(CostReconciliationWorkDO claim) {
        scans.lockActivation();
        var scan = scans.lockActiveScan();
        var now = scans.databaseTime();
        if (scan == null || !Objects.equals(scan.getId(), claim.getScanId()) || now.isBefore(scan.getCutoffAt().plusMinutes(1))) {
            return;
        }
        var row = work.lockRequest(claim.getRequestId());
        if (row != null && Objects.equals(row.getScanId(), scan.getId())) {
            work.defer(row.getId(), claim.getClaimVersion(), now.plusMinutes(1));
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, rollbackFor = Exception.class)
    public List<CostReconciliationWorkDO> claim() {
        var marker = scans.lockActivation();
        if (marker == null || marker.getGeneration() == 0) {
            return List.of();
        }
        var now = scans.databaseTime();
        var scan = scans.lockActiveScan();
        if (scan == null) {
            var due = work.due(marker.getGeneration(), now, BATCH_SIZE);
            if (due.isEmpty()) {
                return List.of();
            }
            scan = new CostReconciliationScanDO();
            scan.setState("OPEN");
            scan.setGeneration(marker.getGeneration());
            scan.setCutoffAt(now);
            scans.insert(scan);
            return claimRows(scan, due, now.plusSeconds(CLAIM_SECONDS));
        }
        if (!scan.getGeneration().equals(marker.getGeneration())) {
            throw new IllegalStateException("active reconciliation scan generation mismatch");
        }
        var rows = new ArrayList<>(work.expired(scan.getGeneration(), scan.getId(), now, BATCH_SIZE));
        if ("OPEN".equals(scan.getState())) {
            if (now.isBefore(scan.getCutoffAt().plusSeconds(DISCOVERY_WINDOW_SECONDS)) && rows.size() < BATCH_SIZE) {
                rows.addAll(work.due(scan.getGeneration(), scan.getCutoffAt(), BATCH_SIZE - rows.size()));
            }
            if (!now.isBefore(scan.getCutoffAt().plusSeconds(DISCOVERY_WINDOW_SECONDS))) {
                scan.setState("CLOSING");
                scans.updateById(scan);
            }
        }
        if ("CLOSING".equals(scan.getState()) && rows.isEmpty()
                && !work.hasUnclassified(scan.getGeneration(), scan.getId())) {
            scan.setState("SEALED");
            scan.setSealedAt(now);
            scans.updateById(scan);
        }
        return claimRows(scan, rows, now.plusSeconds(CLAIM_SECONDS));
    }

    private List<CostReconciliationWorkDO> claimRows(CostReconciliationScanDO scan,
                                                     List<CostReconciliationWorkDO> rows, LocalDateTime lease) {
        for (var row : rows) {
            row.setScanId(scan.getId());
            row.setClaimVersion(Math.addExact(row.getClaimVersion(), 1L));
            row.setNextActionAt(lease);
            work.updateById(row);
        }
        return rows;
    }
}
