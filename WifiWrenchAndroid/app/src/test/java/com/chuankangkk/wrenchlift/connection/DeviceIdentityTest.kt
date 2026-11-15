package com.chuankangkk.wrenchlift.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceIdentityTest {
    @Test fun firstReplyBecomesTheTrustedNumber() {
        assertEquals(DeviceIdentity.Verdict.FirstSeen("1A2B"), DeviceIdentity.evaluate(null, "1A2B"))
        assertEquals(DeviceIdentity.Verdict.FirstSeen("1A2B"), DeviceIdentity.evaluate("  ", " 1a2b "))
    }

    @Test fun sameWrenchMatchesRegardlessOfCase() {
        assertEquals(DeviceIdentity.Verdict.Matches("1A2B"), DeviceIdentity.evaluate("1A2B", "1a2b"))
    }

    /** 不一致只报警，不会被当成新信任值——冒充者回一帧就能顶掉旧编号的话，核对就没意义了。 */
    @Test fun differentNumberIsReportedAndNeverAccepted() {
        val verdict = DeviceIdentity.evaluate("1A2B", "ffff")
        assertEquals(DeviceIdentity.Verdict.Differs(pinned = "1A2B", reported = "FFFF"), verdict)
        assertEquals("1A2B", DeviceIdentity.normalize("1a2b"))
    }

    @Test fun blankReplyKeepsTheTrustedNumber() {
        assertEquals(DeviceIdentity.Verdict.Unknown, DeviceIdentity.evaluate("1A2B", ""))
        assertEquals(DeviceIdentity.Verdict.Unknown, DeviceIdentity.evaluate("1A2B", "   "))
        assertNull(DeviceIdentity.normalize("未知设备"))
        assertNull(DeviceIdentity.normalize("--"))
        assertNull(DeviceIdentity.normalize(null))
    }
}
