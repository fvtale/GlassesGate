package com.glassesgate.core

/**
 * The rule GlassesGate exists to enforce, as a pure function: **the beacon advertises only while
 * a live session with the bound pair of glasses is up, the pair is actually linked, and it is not
 * known to be off the wearer's face.**
 *
 * It lives here rather than in the Android service so that it can be tested exhaustively, with no
 * SDK and no device. The service feeds it what it can observe and does what it says; the service
 * holds no opinion of its own about when to transmit. `BeaconPolicyTest` walks every combination
 * of inputs and asserts the invariant above, so a change that lets the beacon run "just while
 * reconnecting" fails CI rather than shipping.
 *
 * Three conditions, not one, because each closes a gap the others leave:
 *
 * - **Session started.** The SDK's own notion of a live session with this app.
 * - **Pair linked.** Meta's reference code is explicit that `STARTED` means the session exists,
 *   not that the glasses are reachable -- the device can still be arriving, or already gone.
 * - **Not doffed.** DAT 1.0 reports whether the glasses are being worn. Glasses folded on a table
 *   next to the phone are linked; they are not a person standing at a door.
 */
object BeaconPolicy {

    /** The session as the service observes it, flattened out of the SDK's states. */
    enum class Session {
        /** No session object. The start state, and where a stopped session ends up. */
        NONE,
        STARTING,
        STARTED,

        /** Suspended by the SDK -- folded, taken off, or another experience took over. */
        PAUSED,

        /** Stop requested; waiting for the SDK to confirm before a new session may open. */
        STOPPING,

        /** The last attempt to open a session failed. */
        FAILED,
    }

    enum class Wear {
        WORN,
        NOT_WORN,

        /**
         * No answer yet. The SDK documents this as genuinely unknown, not as a doff -- it is what
         * a control channel still warming up reports. It does not block the beacon; [NOT_WORN]
         * does. Requiring a positive [WORN] would be stricter, at the cost of a beacon that
         * refuses to start on any pair whose wear sensor never reports.
         */
        UNKNOWN,
    }

    data class Inputs(
        /** The device the active credential is bound to, or null if there is no credential. */
        val boundDeviceId: String?,
        /**
         * Pairs worth trying to open a session on: those the SDK reports as linked, plus those
         * whose link state has not been reported yet. A pair the SDK says is *not* linked is
         * excluded, so the service does not hammer `createSession` while the glasses sit in
         * their case.
         */
        val candidateDeviceIds: Set<String>,
        val session: Session,
        /** The device the current session was opened on. */
        val sessionDeviceId: String?,
        /** Whether the session's device is linked right now, per its metadata. */
        val sessionDeviceLinked: Boolean,
        val wear: Wear,
        /** False while the service is backing off after a failed attempt. */
        val retryAllowed: Boolean,
    )

    enum class LinkAction { NONE, CONNECT, DISCONNECT }

    /** Why the beacon is or is not on air. Maps one-to-one onto what the wearer is told. */
    enum class Reason {
        BROADCASTING,
        NO_ENROLLMENT,
        GLASSES_NOT_NEARBY,
        CONNECTING,
        NOT_LINKED,
        NOT_WORN,
        PAUSED,
        WRONG_GLASSES,
        RETRY_PENDING,
    }

    data class Decision(val advertise: Boolean, val link: LinkAction, val reason: Reason)

    fun decide(inputs: Inputs): Decision {
        val session = inputs.session
        val bound = inputs.boundDeviceId
            ?: return Decision(
                advertise = false,
                // A session with nothing to prove is a session to close.
                link = if (session.isOpen()) LinkAction.DISCONNECT else LinkAction.NONE,
                reason = Reason.NO_ENROLLMENT,
            )

        return inputs.decideBound(bound)
    }

    private fun Inputs.decideBound(bound: String): Decision =
        when (session) {
            Session.STARTED -> when {
                // The active credential changed to one bound to another pair. Close this session
                // so the next pass can open one on the right glasses.
                sessionDeviceId != bound ->
                    Decision(false, LinkAction.DISCONNECT, Reason.WRONG_GLASSES)
                !sessionDeviceLinked -> Decision(false, LinkAction.NONE, Reason.NOT_LINKED)
                wear == Wear.NOT_WORN -> Decision(false, LinkAction.NONE, Reason.NOT_WORN)
                else -> Decision(true, LinkAction.NONE, Reason.BROADCASTING)
            }

            Session.STARTING, Session.STOPPING ->
                Decision(false, LinkAction.NONE, Reason.CONNECTING)

            // Wait for the SDK to resume or stop the session; forcing a restart from here fights
            // whatever paused it.
            Session.PAUSED -> Decision(false, LinkAction.NONE, Reason.PAUSED)

            Session.NONE, Session.FAILED -> when {
                bound !in candidateDeviceIds ->
                    Decision(false, LinkAction.NONE, Reason.GLASSES_NOT_NEARBY)
                !retryAllowed -> Decision(false, LinkAction.NONE, Reason.RETRY_PENDING)
                else -> Decision(false, LinkAction.CONNECT, Reason.CONNECTING)
            }
        }

    private fun Session.isOpen() =
        this == Session.STARTING || this == Session.STARTED || this == Session.PAUSED
}
