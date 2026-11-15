package app.noonlet

/** One placement request owns the editor until the launcher returns focus. */
internal class WidgetPinRequest {
    private enum class State { IDLE, REQUESTED, SHOWING }
    private var state = State.IDLE
    val isPending get() = state != State.IDLE

    fun request(start: () -> Boolean): Boolean {
        if (state != State.IDLE) return true
        state = State.REQUESTED
        return try {
            start().also { if (!it) state = State.IDLE }
        } catch (error: RuntimeException) {
            state = State.IDLE
            throw error
        }
    }

    fun onWindowFocusChanged(focused: Boolean) {
        if (!focused && state == State.REQUESTED) state = State.SHOWING
        else if (focused && state == State.SHOWING) state = State.IDLE
    }
}
