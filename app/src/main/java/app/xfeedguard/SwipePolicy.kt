package app.xfeedguard

/** Narrow experimental rule from two positive traces and one no-swipe control.
 * No content, Android objects, or navigation side effects belong in this policy.
 */
data class ViewerState(val windowId: Int, val viewer: Boolean, val timeline: Boolean)

data class PageScroll(
    val windowId: Int, val sourceMatchesViewport: Boolean, val sourceScrollable: Boolean,
    val sourceClass: String, val height: Int, val x: Int, val maxX: Int, val y: Int, val maxY: Int
) {
    fun matches(): Boolean = windowId >= 0 && sourceMatchesViewport && sourceScrollable &&
        sourceClass == "android.view.View" && height > 0 && x == 0 && maxX == 0 &&
        y >= height && y % height == 0 && y <= maxY && maxY.toLong() >= height.toLong() * 3 && maxY % height == 0
}

class SwipePolicy {
    private var viewerWindow = -1
    private var viewerSince = -1L
    private var viewerLastSeen = -1L
    private var latched = false
    private var lastAttempt = -1L
    private var pendingWindow = -1
    private var pendingSince = -1L
    private var pendingViewerSince = -1L
    val hasPending: Boolean get() = pendingWindow >= 0

    fun reset() {
        viewerWindow = -1; viewerSince = -1; viewerLastSeen = -1; latched = false
        pendingWindow = -1; pendingSince = -1; pendingViewerSince = -1
        // Retain the cooldown across toggles and screen changes.
    }

    fun observe(state: ViewerState, now: Long) {
        if (state.timeline) { reset(); return }
        if (!state.viewer || state.windowId < 0) {
            viewerWindow = -1; viewerSince = -1; viewerLastSeen = -1
            return
        }
        if (viewerWindow != state.windowId) {
            viewerWindow = state.windowId; viewerSince = now
        }
        viewerLastSeen = now
    }

    /** Retain structural evidence briefly while the next video's controls finish loading.
     * A known viewer must precede the scroll; a static viewer alone never arms recovery.
     */
    fun remember(scroll: PageScroll, now: Long): Boolean {
        if (latched || viewerWindow < 0 || viewerSince < 0 || now < viewerSince ||
            scroll.windowId != viewerWindow || !scroll.matches()) return false
        if (!hasPending) {
            pendingWindow = scroll.windowId
            pendingSince = now
            pendingViewerSince = viewerSince
        }
        return true
    }

    /** Consume before attempting Back. No automatic navigation retry loop. */
    fun recover(state: ViewerState, now: Long): Boolean {
        if (!hasPending) return false
        if (now < pendingSince || now - pendingSince > 3_000 || state.timeline ||
            (state.windowId >= 0 && state.windowId != pendingWindow)) {
            pendingWindow = -1
            return false
        }
        if (latched || !state.viewer || state.windowId != pendingWindow ||
            now < pendingViewerSince || now - pendingViewerSince < 300) return false
        if (lastAttempt >= 0 && now - lastAttempt < 5_000) return false
        latched = true
        pendingWindow = -1
        lastAttempt = now
        return true
    }

    fun consume(scroll: PageScroll, state: ViewerState, now: Long): Boolean {
        remember(scroll, now)
        return recover(state, now)
    }
}
