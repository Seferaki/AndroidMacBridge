package dev.seferaki.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferOutcomeTest {
    @Test fun uncertainResultsNeverBecomeSuccess() {
        assertEquals(TransferOutcome.COMPLETE, transferOutcome("complete"))
        assertEquals(TransferOutcome.PENDING, transferOutcome("transferring"))
        assertEquals(TransferOutcome.FAILED, transferOutcome("failed"))
        assertEquals(TransferOutcome.CANCELLED, transferOutcome("cancelled"))
        assertEquals(TransferOutcome.UNKNOWN, transferOutcome(null))
        assertEquals(TransferOutcome.UNKNOWN, transferOutcome("unexpected"))
    }
    @Test fun cancellationWaitsWhenResultIsUnknown() {
        assertEquals(CancellationDecision.WAIT, cancellationDecision(TransferOutcome.UNKNOWN))
        assertEquals(CancellationDecision.SEND, cancellationDecision(TransferOutcome.PENDING))
        assertEquals(CancellationDecision.CLEAR, cancellationDecision(TransferOutcome.COMPLETE))
        assertEquals(CancellationDecision.CLEAR, cancellationDecision(TransferOutcome.CANCELLED))
    }
}
