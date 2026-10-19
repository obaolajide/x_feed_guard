package app.xfeedguard

fun main() {
    testSwipePolicy()
    testProtectionStatus()
    check(TransitionPlan.phaseAt(7_999) == "timeline_baseline")
    check(TransitionPlan.phaseAt(8_000) == "single_video")
    check(TransitionPlan.phaseAt(18_000) == "next_video")
    check(TransitionPlan.phaseAt(30_000) == "return_timeline")
    val flood = TraceBudget()
    check((0 until 10_000).count { flood.allow("content", 0) } == 1)
    check(flood.allow("scroll", 1)) // A content flood must not starve a swipe.
    check(!flood.allow("scroll", 40_000)) // Recording stops at the deadline.
    check(!flood.allow("window", -1))
    val caps = TraceBudget()
    check((0 until 40_000).count { caps.allow("content", it.toLong()) } == 160)
    check((0 until 1_000).count { caps.allow("scroll", it.toLong()) } == 400)
    check(!caps.allow("window", 2_000))
    println("PASS: phase boundaries, content throttling, scroll budget, event caps, and deadline")
}
