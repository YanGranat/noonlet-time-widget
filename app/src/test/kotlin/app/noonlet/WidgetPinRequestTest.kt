package app.noonlet

import org.junit.Assert.*
import org.junit.Test

class WidgetPinRequestTest {
    @Test
    fun repeatedTapsShareOneRequestUntilTheLauncherReturns() {
        val flow = WidgetPinRequest()
        var requests = 0
        repeat(3) { assertTrue(flow.request { requests++; true }) }
        assertEquals(1, requests)
        flow.onWindowFocusChanged(false)
        flow.onWindowFocusChanged(true)
        assertTrue(flow.request { requests++; true })
        assertEquals(2, requests)
    }

    @Test
    fun rejectedRequestsCanBeRetriedWithoutAWindowTransition() {
        val flow = WidgetPinRequest()
        assertFalse(flow.request { false })
        var retried = false
        assertTrue(flow.request { retried = true; true })
        assertTrue(retried)
    }
}
