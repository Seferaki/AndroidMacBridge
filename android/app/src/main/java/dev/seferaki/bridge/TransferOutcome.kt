package dev.seferaki.bridge

internal enum class TransferOutcome { COMPLETE, FAILED, CANCELLED, PENDING, UNKNOWN }
internal enum class CancellationDecision { CLEAR, SEND, WAIT }

internal fun cancellationDecision(outcome: TransferOutcome): CancellationDecision = when (outcome) {
    TransferOutcome.COMPLETE, TransferOutcome.FAILED, TransferOutcome.CANCELLED -> CancellationDecision.CLEAR
    TransferOutcome.PENDING -> CancellationDecision.SEND
    TransferOutcome.UNKNOWN -> CancellationDecision.WAIT
}

internal fun transferOutcome(state: String?): TransferOutcome = when (state) {
    "complete" -> TransferOutcome.COMPLETE
    "failed", "rejected" -> TransferOutcome.FAILED
    "cancelled" -> TransferOutcome.CANCELLED
    "offered", "accepted", "transferring", "pending" -> TransferOutcome.PENDING
    else -> TransferOutcome.UNKNOWN
}
