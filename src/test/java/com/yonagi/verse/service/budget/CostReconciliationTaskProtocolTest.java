package com.yonagi.verse.service.budget;

import com.yonagi.verse.async.event.CostReconciliationTaskEvent;
import com.yonagi.verse.async.outbox.DomainEventPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.function.Consumer;
import java.util.stream.Stream;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** 冻结载荷的解析、规范摘要与边界算法。 */
class CostReconciliationTaskProtocolTest {
    static Stream<Consumer<CostReconciliationTaskEvent>> invalidEnvelope() {
        return Stream.of(e -> e.setSchemaVersion(null), e -> e.setSchemaVersion(2),
                e -> e.setTaskId(null), e -> e.setTaskId("x".repeat(65)), e -> e.setEventId("foreign"),
                e -> e.setRunId(null), e -> e.setRunId(0L), e -> e.setGeneration(null), e -> e.setGeneration(0L),
                e -> e.setTenantId(null), e -> e.setTenantId(0L), e -> e.setOperation(null));
    }

    @ParameterizedTest
    @MethodSource("invalidEnvelope")
    void rejectsEveryMissingOrForeignEnvelopeIdentity(Consumer<CostReconciliationTaskEvent> change) {
        var event=page();change.accept(event);sign(event);
        assertThrows(IllegalArgumentException.class,()->CostReconciliationTaskProtocol.validate(event));
    }

    static Stream<List<String>> invalidRequests() {
        return Stream.of(java.util.Arrays.asList((String)null),List.of(" "),List.of("x".repeat(65)),List.of());
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void rejectsUnusableRequestIdentityLists(List<String> requests) {
        var event=page();event.setRequestIds(requests);sign(event);
        assertThrows(IllegalArgumentException.class,()->CostReconciliationTaskProtocol.validate(event));
    }

    static Stream<CostReconciliationTaskEvent.CaseVersion> invalidCases() {
        return Stream.of(null,new CostReconciliationTaskEvent.CaseVersion(null,0L),
                new CostReconciliationTaskEvent.CaseVersion(0L,0L),
                new CostReconciliationTaskEvent.CaseVersion(7L,null),
                new CostReconciliationTaskEvent.CaseVersion(7L,-1L));
    }

    @ParameterizedTest
    @MethodSource("invalidCases")
    void rejectsUnusableCaseIdentityOrVersion(CostReconciliationTaskEvent.CaseVersion member) {
        var event=page();event.setRequestIds(null);
        event.setOperation(CostReconciliationTaskEvent.Operation.EXPIRE_AUTOMATIC_CASES);
        event.setCases(java.util.Arrays.asList(member));sign(event);
        assertThrows(IllegalArgumentException.class,()->CostReconciliationTaskProtocol.validate(event));
    }

    @Test
    void missingMembersAndOversizeSerializedEnvelopeAreRejected() {
        var event=page();event.setRequestIds(null);sign(event);
        assertThrows(IllegalArgumentException.class,()->CostReconciliationTaskProtocol.validate(event));
        event.setOperation(CostReconciliationTaskEvent.Operation.EXPIRE_AUTOMATIC_CASES);sign(event);
        assertThrows(IllegalArgumentException.class,()->CostReconciliationTaskProtocol.validate(event));
        var oversized=page();oversized.setKey("x".repeat(CostReconciliationTaskProtocol.MAX_BYTES));sign(oversized);
        assertThrows(IllegalArgumentException.class,()->CostReconciliationTaskProtocol.validate(oversized));
    }
    @Test
    void canonicalHashIgnoresObjectKeyOrderButPreservesFrozenMemberOrder() {
        assertEquals(DomainEventPayload.hash(Map.of("a", 1, "b", 2)), DomainEventPayload.hash("{\"b\":2,\"a\":1}"));
        var event = page();
        String original = CostReconciliationTaskProtocol.hash(event);
        event.setRequestIds(List.of("second", "first"));
        assertNotEquals(original, CostReconciliationTaskProtocol.hash(event));
    }

    @Test
    void exact100MemberPageIsAcceptedAndOverLimitOrDuplicateMembersAreRejected() {
        var event = page();
        event.setRequestIds(IntStream.range(0,100).mapToObj(Integer::toString).toList());
        sign(event); CostReconciliationTaskProtocol.validate(event);
        assertEquals(100, event.getRequestIds().size());
        event.setRequestIds(IntStream.range(0,101).mapToObj(Integer::toString).toList());
        sign(event); assertThrows(IllegalArgumentException.class, () -> CostReconciliationTaskProtocol.validate(event));
        event.setRequestIds(List.of("same","same")); sign(event);
        assertThrows(IllegalArgumentException.class, () -> CostReconciliationTaskProtocol.validate(event));
    }

    @Test
    void mixedOperationsMissingEnvelopeAndTamperedHashFailClosed() {
        var event = page(); sign(event);
        event.setPayloadHash("tampered");
        assertThrows(IllegalArgumentException.class, () -> CostReconciliationTaskProtocol.validate(event));
        event.setCases(List.of(new CostReconciliationTaskEvent.CaseVersion(7L,0L))); sign(event);
        assertThrows(IllegalArgumentException.class, () -> CostReconciliationTaskProtocol.validate(event));
        event.setSchemaVersion(2);
        assertThrows(IllegalArgumentException.class, () -> CostReconciliationTaskProtocol.validate(event));
        assertThrows(IllegalArgumentException.class, () -> CostReconciliationTaskProtocol.validate(null));
    }

    @Test
    void expireOperationRequiresUniqueCaseIdentityAndNonnegativeVersion() {
        var event = page(); event.setRequestIds(null);
        event.setOperation(CostReconciliationTaskEvent.Operation.EXPIRE_AUTOMATIC_CASES);
        event.setCases(List.of(new CostReconciliationTaskEvent.CaseVersion(7L,0L))); sign(event);
        CostReconciliationTaskProtocol.validate(event);
        assertEquals(0L,event.getCases().getFirst().version());
        event.setCases(List.of(new CostReconciliationTaskEvent.CaseVersion(7L,-1L))); sign(event);
        assertThrows(IllegalArgumentException.class, () -> CostReconciliationTaskProtocol.validate(event));
        event.setCases(List.of(new CostReconciliationTaskEvent.CaseVersion(7L,1L),
                new CostReconciliationTaskEvent.CaseVersion(7L,2L))); sign(event);
        assertThrows(IllegalArgumentException.class, () -> CostReconciliationTaskProtocol.validate(event));
    }

    private void sign(CostReconciliationTaskEvent event) { event.setPayloadHash(CostReconciliationTaskProtocol.hash(event)); }

    private CostReconciliationTaskEvent page() {
        var event = new CostReconciliationTaskEvent();
        event.setSchemaVersion(1);event.setEventId("crt-3-0");event.setTaskId("crt-3-0");
        event.setRunId(3L);event.setTenantId(7L);event.setGeneration(2L);
        event.setOperation(CostReconciliationTaskEvent.Operation.CLASSIFY_REQUESTS);
        event.setRequestIds(List.of("first","second"));return event;
    }
}
