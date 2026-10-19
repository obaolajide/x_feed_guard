package app.xfeedguard

object TransitionPlan {
    const val DURATION_MS = 40_000L
    const val SNAPSHOT_MS = 2_000L
    fun phaseAt(elapsed: Long): String = when {
        elapsed < 8_000 -> "timeline_baseline"
        elapsed < 18_000 -> "single_video"
        elapsed < 30_000 -> "next_video"
        else -> "return_timeline"
    }
}

/** Content updates cannot consume the separate budget reserved for scroll/window events. */
class TraceBudget {
    private var lastContentAt = -250L
    private var contentCount = 0
    private var otherCount = 0
    var dropped = 0
        private set
    fun allow(type: String, elapsed: Long): Boolean {
        val allowed = when {
            elapsed < 0 || elapsed >= TransitionPlan.DURATION_MS -> false
            type == "content" -> contentCount < 160 && elapsed - lastContentAt >= 250
            else -> otherCount < 400
        }
        if (!allowed) { dropped++; return false }
        if (type == "content") { contentCount++; lastContentAt = elapsed } else otherCount++
        return true
    }
}
