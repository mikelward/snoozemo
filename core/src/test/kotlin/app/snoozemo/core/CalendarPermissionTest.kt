package app.snoozemo.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The three states, and specifically the two that read identically from the
 * platform — see [CalendarPermission]'s own doc.
 */
class CalendarPermissionTest {

    @Test
    fun `held is granted whatever the history says`() {
        assertEquals(
            CalendarPermission.GRANTED,
            CalendarPermission.of(granted = true, everDenied = true, rationale = true),
        )
    }

    @Test
    fun `a fresh install is askable`() {
        // Never denied, so `rationale` reads false because there is nothing to
        // explain yet — not because the prompt is spent.
        assertEquals(
            CalendarPermission.ASKABLE,
            CalendarPermission.of(granted = false, everDenied = false, rationale = false),
        )
    }

    @Test
    fun `denied once is still askable`() {
        assertEquals(
            CalendarPermission.ASKABLE,
            CalendarPermission.of(granted = false, everDenied = true, rationale = true),
        )
    }

    @Test
    fun `denied with the prompt spent is blocked`() {
        // The reading that matters: `rationale` false again, but the history
        // says a denial landed — so the system has stopped prompting and the
        // row must point at Settings instead of offering a tap that does
        // nothing.
        assertEquals(
            CalendarPermission.BLOCKED,
            CalendarPermission.of(granted = false, everDenied = true, rationale = false),
        )
    }
}
