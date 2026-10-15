package com.glassesgate.core

import com.glassesgate.core.BeaconPolicy.Decision
import com.glassesgate.core.BeaconPolicy.Inputs
import com.glassesgate.core.BeaconPolicy.LinkAction
import com.glassesgate.core.BeaconPolicy.Reason
import com.glassesgate.core.BeaconPolicy.Session
import com.glassesgate.core.BeaconPolicy.Wear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconPolicyTest {

    private val bound = "glasses-A"
    private val other = "glasses-B"

    /** A healthy, broadcasting setup; each test perturbs one thing. */
    private val live = Inputs(
        boundDeviceId = bound,
        candidateDeviceIds = setOf(bound),
        session = Session.STARTED,
        sessionDeviceId = bound,
        sessionDeviceLinked = true,
        wear = Wear.WORN,
        retryAllowed = true,
    )

    /** Every combination of inputs worth distinguishing: 2 x 4 x 6 x 3 x 2 x 3 x 2 = 1728. */
    private fun everyInput(): Sequence<Inputs> = sequence {
        for (boundId in listOf(null, bound))
            for (candidates in listOf(emptySet(), setOf(bound), setOf(other), setOf(bound, other)))
                for (session in Session.entries)
                    for (sessionDevice in listOf(null, bound, other))
                        for (linked in listOf(true, false))
                            for (wear in Wear.entries)
                                for (retry in listOf(true, false))
                                    yield(
                                        Inputs(
                                            boundDeviceId = boundId,
                                            candidateDeviceIds = candidates,
                                            session = session,
                                            sessionDeviceId = sessionDevice,
                                            sessionDeviceLinked = linked,
                                            wear = wear,
                                            retryAllowed = retry,
                                        ),
                                    )
    }

    @Test
    fun `the exhaustive walk covers as many cases as the README says`() {
        assertEquals(1728, everyInput().count())
    }

    // ---- The invariant ----

    @Test
    fun `never advertises without a live, linked, not-doffed session on the bound pair`() {
        for (inputs in everyInput()) {
            val decision = BeaconPolicy.decide(inputs)
            if (!decision.advertise) continue
            val ok = inputs.boundDeviceId != null &&
                inputs.session == Session.STARTED &&
                inputs.sessionDeviceId == inputs.boundDeviceId &&
                inputs.sessionDeviceLinked &&
                inputs.wear != Wear.NOT_WORN
            assertTrue("advertised for $inputs", ok)
        }
    }

    @Test
    fun `advertises in every state that satisfies the rule`() {
        // The converse: the policy must not be so strict that the beacon never runs.
        for (inputs in everyInput()) {
            val shouldAdvertise = inputs.boundDeviceId != null &&
                inputs.session == Session.STARTED &&
                inputs.sessionDeviceId == inputs.boundDeviceId &&
                inputs.sessionDeviceLinked &&
                inputs.wear != Wear.NOT_WORN
            assertEquals("for $inputs", shouldAdvertise, BeaconPolicy.decide(inputs).advertise)
        }
    }

    @Test
    fun `never asks to connect while a session is already open`() {
        for (inputs in everyInput()) {
            if (BeaconPolicy.decide(inputs).link != LinkAction.CONNECT) continue
            assertTrue(
                "connected over an open session for $inputs",
                inputs.session == Session.NONE || inputs.session == Session.FAILED,
            )
        }
    }

    @Test
    fun `only ever connects to the bound pair, and only when it is a candidate`() {
        for (inputs in everyInput()) {
            if (BeaconPolicy.decide(inputs).link != LinkAction.CONNECT) continue
            assertTrue("for $inputs", inputs.boundDeviceId in inputs.candidateDeviceIds)
            assertTrue("for $inputs", inputs.retryAllowed)
        }
    }

    @Test
    fun `advertising and changing the link never happen in the same decision`() {
        for (inputs in everyInput()) {
            val decision = BeaconPolicy.decide(inputs)
            if (decision.advertise) assertEquals("for $inputs", LinkAction.NONE, decision.link)
        }
    }

    // ---- Each reason, named ----

    @Test
    fun `a live session on the bound pair broadcasts`() {
        assertEquals(
            Decision(true, LinkAction.NONE, Reason.BROADCASTING),
            BeaconPolicy.decide(live),
        )
    }

    @Test
    fun `unknown wear does not block the beacon`() {
        // See Wear.UNKNOWN for why this is the line, and what tightening it would cost.
        assertTrue(BeaconPolicy.decide(live.copy(wear = Wear.UNKNOWN)).advertise)
    }

    @Test
    fun `glasses taken off stop the beacon without dropping the session`() {
        assertEquals(
            Decision(false, LinkAction.NONE, Reason.NOT_WORN),
            BeaconPolicy.decide(live.copy(wear = Wear.NOT_WORN)),
        )
    }

    @Test
    fun `a started session whose pair is not linked does not count`() {
        assertEquals(
            Decision(false, LinkAction.NONE, Reason.NOT_LINKED),
            BeaconPolicy.decide(live.copy(sessionDeviceLinked = false)),
        )
    }

    @Test
    fun `a session on some other pair is closed`() {
        assertEquals(
            Decision(false, LinkAction.DISCONNECT, Reason.WRONG_GLASSES),
            BeaconPolicy.decide(live.copy(sessionDeviceId = other)),
        )
    }

    @Test
    fun `removing the enrollment closes any open session`() {
        assertEquals(
            Decision(false, LinkAction.DISCONNECT, Reason.NO_ENROLLMENT),
            BeaconPolicy.decide(live.copy(boundDeviceId = null)),
        )
        assertEquals(
            Decision(false, LinkAction.NONE, Reason.NO_ENROLLMENT),
            BeaconPolicy.decide(live.copy(boundDeviceId = null, session = Session.NONE)),
        )
    }

    @Test
    fun `no session and the pair is in range means connect`() {
        assertEquals(
            Decision(false, LinkAction.CONNECT, Reason.CONNECTING),
            BeaconPolicy.decide(live.copy(session = Session.NONE, sessionDeviceId = null)),
        )
    }

    @Test
    fun `a dropped session reconnects once the pair is back`() {
        // The bug this module was written to stop recurring: after the glasses went out of range
        // once, the old service never tried again.
        val dropped = live.copy(session = Session.NONE, sessionDeviceId = null)
        assertEquals(
            Reason.GLASSES_NOT_NEARBY,
            BeaconPolicy.decide(dropped.copy(candidateDeviceIds = emptySet())).reason,
        )
        assertEquals(LinkAction.CONNECT, BeaconPolicy.decide(dropped).link)
    }

    @Test
    fun `a failure backs off rather than retrying in a tight loop`() {
        val failed = live.copy(session = Session.FAILED, sessionDeviceId = null)
        assertEquals(
            Decision(false, LinkAction.NONE, Reason.RETRY_PENDING),
            BeaconPolicy.decide(failed.copy(retryAllowed = false)),
        )
        assertEquals(LinkAction.CONNECT, BeaconPolicy.decide(failed).link)
    }

    @Test
    fun `transitional states wait rather than act`() {
        for (session in listOf(Session.STARTING, Session.STOPPING, Session.PAUSED)) {
            val decision = BeaconPolicy.decide(live.copy(session = session))
            assertFalse(decision.advertise)
            assertEquals("for $session", LinkAction.NONE, decision.link)
        }
    }
}
