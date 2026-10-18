package com.yonagi.verse.service.budget;

import com.yonagi.verse.service.forward.UpstreamExecutionOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class InvocationExecutionStateTest {
    private final Instant now = Instant.parse("2026-10-18T03:00:00Z");

    @Test void cancellationBeforeSendingPermanentlyClosesThisAttemptAndRetainsCleanupProof() {
        var state = new InvocationExecutionState(BudgetInvocationMode.SYNC);
        var attempt = state.attempt();
        assertEquals(UpstreamExecutionOutcome.NOT_SENT, attempt.cancel());
        assertFalse(state.active()); assertFalse(state.sent());
        assertTrue(state.noUpstream()); assertTrue(state.noUpstream());
        assertThrows(CostBudgetUnavailableException.class, attempt::startSending);
        assertThrows(CostBudgetUnavailableException.class, state::attempt);
        state.finalizing(); assertTrue(state.cleanupPending(now));
        state.deferCleanup(now); assertFalse(state.cleanupPending(now.plusSeconds(9)));
        assertTrue(state.cleanupPending(now.plusSeconds(10)));
        state.close(); assertFalse(state.noUpstream());
        assertThrows(CostBudgetUnavailableException.class, attempt::checkActive);
    }

    @Test void sentCancellationRemainsUnknownAndCannotBeAbandonedOrRetried() {
        var state = new InvocationExecutionState(BudgetInvocationMode.MEDIA);
        var attempt = state.attempt(); attempt.startSending();
        assertEquals(UpstreamExecutionOutcome.UNKNOWN, state.outcome());
        assertEquals(UpstreamExecutionOutcome.UNKNOWN, attempt.cancel());
        attempt.complete(UpstreamExecutionOutcome.NOT_SENT);
        assertFalse(state.noUpstream()); assertFalse(state.cleanupPending(now));
        assertThrows(CostBudgetUnavailableException.class, state::attempt);
    }

    @ParameterizedTest @EnumSource(value=UpstreamExecutionOutcome.class, names={"REJECTED","NOT_SENT"})
    void safeRetryUsesFreshHandleAndStaleHandleCannotSendOrCancelIt(UpstreamExecutionOutcome outcome) {
        var state = new InvocationExecutionState(BudgetInvocationMode.SYNC);
        var first = state.attempt(); first.startSending(); first.complete(outcome);
        var next = state.attempt();
        assertEquals(outcome, state.outcome());
        assertThrows(CostBudgetUnavailableException.class, first::startSending);
        assertEquals(outcome, first.cancel());
        assertTrue(state.active()); next.startSending();
        next.complete(UpstreamExecutionOutcome.COMPLETED);
        assertEquals(UpstreamExecutionOutcome.COMPLETED, state.outcome());
        assertThrows(CostBudgetUnavailableException.class, state::attempt);
        assertFalse(state.noUpstream());
    }

    @ParameterizedTest @EnumSource(BudgetInvocationMode.class)
    void ageDoesNotExpirePreparationOrActiveExecution(BudgetInvocationMode mode) {
        var state = new InvocationExecutionState(mode);
        assertFalse(state.expireWaiting(now.plusSeconds(600)));
        if (mode == BudgetInvocationMode.STREAM) { state.prepared(now); state.subscribe(); }
        state.attempt().startSending();
        assertFalse(state.expireWaiting(now.plusSeconds(600))); assertTrue(state.active());
    }

    @Test void waitingClockBeginsAfterPreparationAndExpiryForbidsSubscription() {
        var state = new InvocationExecutionState(BudgetInvocationMode.STREAM);
        assertThrows(CostBudgetUnavailableException.class, state::subscribe);
        assertThrows(CostBudgetUnavailableException.class, state::attempt);
        state.prepared(now);
        assertThrows(CostBudgetUnavailableException.class, () -> state.prepared(now));
        assertFalse(state.expireWaiting(now.plusSeconds(29)));
        assertTrue(state.expireWaiting(now.plusSeconds(30)));
        assertFalse(state.expireWaiting(now.plusSeconds(31)));
        assertThrows(CostBudgetUnavailableException.class, state::subscribe);
    }

    @Test void subscriptionAndExpiryHaveExactlyOneWinner() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            for (int i=0; i<200; i++) {
                var state = new InvocationExecutionState(BudgetInvocationMode.STREAM); state.prepared(now);
                var barrier = new CyclicBarrier(2);
                var subscription = workers.submit(() -> { barrier.await(); try { state.subscribe(); return true; }
                    catch (CostBudgetUnavailableException expired) { return false; } });
                var expiry = workers.submit(() -> { barrier.await(); return state.expireWaiting(now.plusSeconds(30)); });
                assertNotEquals(subscription.get(2,TimeUnit.SECONDS), expiry.get(2,TimeUnit.SECONDS));
                assertNotEquals(state.active(), state.cleanupPending(now));
            }
        }
    }

    @Test void sendingAndCancellationAgreeOnTheOnlyPossibleExecution() throws Exception {
        try (var workers = Executors.newFixedThreadPool(2)) {
            for (int i=0; i<200; i++) {
                var state = new InvocationExecutionState(BudgetInvocationMode.SYNC); var attempt = state.attempt();
                var barrier = new CyclicBarrier(2);
                var sending = workers.submit(() -> { barrier.await(); try { attempt.startSending(); return true; }
                    catch (CostBudgetUnavailableException cancelled) { return false; } });
                var cancellation = workers.submit(() -> { barrier.await(); return attempt.cancel(); });
                assertEquals(sending.get(2,TimeUnit.SECONDS) ? UpstreamExecutionOutcome.UNKNOWN : UpstreamExecutionOutcome.NOT_SENT,
                        cancellation.get(2,TimeUnit.SECONDS));
                assertFalse(state.active());
            }
        }
    }

    @Test void firstTerminalFailureWinsAndFinalizingOrClosedCannotSend() {
        var state = new InvocationExecutionState(BudgetInvocationMode.SYNC);
        var attempt = state.attempt();
        state.fail("UPSTREAM", null); assertNull(state.failure());
        state.fail("BUDGET_CHECK", "B000802"); state.fail("SETTLEMENT", "SETTLEMENT_FAILED");
        assertEquals(new InvocationExecutionState.Failure("BUDGET_CHECK","B000802"), state.failure());
        state.finalizing(); assertFalse(state.active()); assertFalse(state.noUpstream());
        assertThrows(CostBudgetUnavailableException.class, attempt::startSending);
        state.close(); assertEquals(UpstreamExecutionOutcome.NOT_SENT, attempt.cancel()); assertFalse(state.active());
    }
}
