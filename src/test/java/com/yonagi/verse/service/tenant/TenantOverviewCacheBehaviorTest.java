package com.yonagi.verse.service.tenant;

import com.yonagi.verse.dto.resp.TenantOverviewRespDTO.Batch;
import com.yonagi.verse.dto.resp.TenantOverviewRespDTO.Detail;
import com.yonagi.verse.dto.resp.TenantOverviewRespDTO.Item;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TenantOverviewCacheBehaviorTest {
    private final TenantOverviewCacheBehavior policy = new TenantOverviewCacheBehavior();

    @Test
    void zeroCountsAndAnEmptyMembershipListAreCompleteResults() {
        Detail detail = new Detail();
        detail.setMemberCount(0L);
        detail.setAvailableServiceCount(0L);
        Batch empty = new Batch();
        empty.setItems(List.of());
        Batch populated = new Batch();
        populated.setItems(List.of(detail));

        assertTrue(policy.cacheable(detail));
        assertTrue(policy.cacheable(empty));
        assertTrue(policy.cacheable(populated));
    }

    @Test
    void anUnavailableCountMakesTheEntireOverviewIncomplete() {
        Detail missingMemberCount = new Detail();
        missingMemberCount.setAvailableServiceCount(2L);
        Item missingServiceCount = new Item();
        missingServiceCount.setMemberCount(3L);
        Item complete = new Item();
        complete.setMemberCount(3L);
        complete.setAvailableServiceCount(2L);
        Batch batch = new Batch();
        batch.setItems(List.of(complete, missingServiceCount));

        assertFalse(policy.cacheable(missingMemberCount));
        assertFalse(policy.cacheable(batch));
    }

    @Test
    void missingResultsAreDistinctFromAnEmptyMembershipList() {
        Batch absentList = new Batch();
        Batch absentItem = new Batch();
        absentItem.setItems(Collections.singletonList(null));

        assertFalse(policy.cacheable(null));
        assertFalse(policy.cacheable(absentList));
        assertFalse(policy.cacheable(absentItem));
    }
}
