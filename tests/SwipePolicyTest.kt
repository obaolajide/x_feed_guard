package app.xfeedguard

fun testSwipePolicy() {
    val viewer = ViewerState(3940, true, false)
    val timeline = ViewerState(3940, false, true)
    val loading = ViewerState(3940, false, false)
    val first = PageScroll(3940, true, true, "android.view.View", 1600, 0, 0, 1600, 19200)
    check(first.matches() && first.copy(maxY = 20800).matches())
    check(first.copy(y = 3200).matches()) // A missed first page does not disable later pages.
    check(first.copy(y = 19200).matches())
    for ((y, maxY) in listOf(0 to 100, 1304 to 1404, 1484 to 1584, 18984 to 19084)) {
        check(!first.copy(y = y, maxY = maxY).matches())
    }
    for (invalid in listOf(first.copy(sourceMatchesViewport = false), first.copy(sourceScrollable = false),
        first.copy(sourceClass = "android.widget.SeekBar"), first.copy(y = -1, maxY = -1),
        first.copy(height = 0), first.copy(y = 1599), first.copy(y = 0), first.copy(y = 20800),
        first.copy(maxY = 19201), first.copy(x = 1), first.copy(maxX = -1), first.copy(windowId = -1))) {
        check(!invalid.matches())
    }
    fun baseline() = SwipePolicy().also { it.observe(viewer, 1000) }
    check(!SwipePolicy().consume(first, viewer, 1500)) // No known viewer before the swipe.
    check(!baseline().consume(first.copy(windowId = 99), viewer, 1500))
    check(!baseline().consume(first, timeline, 1500))
    check(baseline().consume(first, viewer, 60_000)) // Quiet playback does not expire the session.
    check(baseline().consume(first.copy(y = 3200), viewer, 1500))
    val policy = baseline()
    check(!policy.recover(viewer, 1600)) // Playback alone is never enough.
    check(policy.remember(first, 1700))
    policy.observe(loading, 1700)
    check(!policy.recover(loading, 1950))
    check(policy.recover(viewer, 2450)) // Controls appeared after a delayed load.
    check(!policy.recover(viewer, 9000))
    check(!policy.consume(first.copy(y = 3200), viewer, 10000)) // One attempt per viewer session.
    policy.observe(loading, 11000)
    policy.observe(viewer, 11500)
    check(!policy.consume(first, viewer, 12000)) // Unknown structures do not release the latch.
    policy.observe(timeline, 13000)
    policy.observe(viewer, 13100)
    check(policy.consume(first, viewer, 13500))
    policy.reset()
    policy.observe(viewer, 13600)
    check(!policy.consume(first, viewer, 14000)) // Preserve cooldown across toggles.
    val expired = baseline()
    expired.remember(first, 1500)
    check(!expired.recover(viewer, 4501) && !expired.hasPending)
    val left = baseline()
    left.remember(first, 1500)
    left.observe(timeline, 1600)
    check(!left.recover(viewer, 1800))
    val window = baseline()
    window.remember(first, 1500)
    check(!window.recover(viewer.copy(windowId = 99), 1800) && !window.hasPending)
    val reset = baseline()
    reset.remember(first, 1500)
    reset.reset()
    check(!reset.recover(viewer, 1900))
    val animation = baseline()
    check(!animation.consume(first, viewer, 1100))
    check(animation.recover(viewer, 1400))
    println("PASS: first/later pages, negative geometry, quiet playback, bounded loading recovery, session latch, and cooldown")
}
