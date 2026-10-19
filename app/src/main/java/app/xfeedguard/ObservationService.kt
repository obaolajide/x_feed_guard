package app.xfeedguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityButtonController
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** X-only observation, with a separate, opt-in experimental swipe guard. */
class ObservationService : AccessibilityService() {
    companion object {
        const val X_PACKAGE = "com.twitter.android"
        private var reference = WeakReference<ObservationService>(null)
        val current: ObservationService? get() = reference.get()
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: GuardStore
    private var captureTask: Runnable? = null
    private val eventCounts = mutableMapOf<String, Int>()
    private val traceToken = Any()
    private val recoveryToken = Any()
    private var traceStart = 0L
    private var traceWallStart = 0L
    private var traceEvents = JSONArray()
    private var traceSnapshots = JSONArray()
    private var traceBudget = TraceBudget()
    private var skippedSnapshots = 0
    private val swipePolicy = SwipePolicy()
    private var lastInspection = -1L
    @Volatile private var connected = false
    private val guardGeneration = AtomicInteger()
    private var appliedGeneration = -1
    private val contentQueued = AtomicBoolean(false)
    private val guardWorker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(4), { task -> Thread(task, "XFeedGuard-inspection") })
    private val shortcutCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            try {
                startActivity(Intent(this@ObservationService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (_: Exception) {
                Toast.makeText(this@ObservationService, "Open X Feed Guard from your home screen to turn protection on or off.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val supportedBlockingBuild: Boolean get() = Build.VERSION.SDK_INT == 35 &&
        Build.MODEL == "23106RN0DA" && try {
            packageManager.getPackageInfo(X_PACKAGE, 0).versionName == "12.35.0-prod.01"
        } catch (_: Exception) { false }
    var captureDeadline = 0L
        private set
    @Volatile var pendingLabel: String? = null
        private set
    var eventTotal = 0L
        private set
    var lastEventAt = 0L
        private set

    override fun onServiceConnected() {
        store = GuardStore(this)
        // Preserve the user's explicit choice across Android-managed reconnection.
        // New installs still default off; runtime checks require a fresh viewer baseline.
        connected = true
        resetGuard()
        reference = WeakReference(this)
        accessibilityButtonController.registerAccessibilityButtonCallback(shortcutCallback, handler)
        ProtectionService.sync(this, store)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::store.isInitialized) return
        if (!store.observing || event?.packageName?.toString() != X_PACKAGE) {
            resetGuard()
            return
        }
        eventTotal++
        lastEventAt = SystemClock.elapsedRealtime()
        if (pendingLabel != null) {
            val type = when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window"
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "content"
                else -> "other"
            }
            eventCounts[type] = (eventCounts[type] ?: 0) + 1
        }
        if (pendingLabel == "transition") recordTraceEvent(event)
        if (pendingLabel == null && store.blocking) queueInspection(event)
        // No event text, activity label, or content description is retained.
    }

    fun resetGuard() {
        guardGeneration.incrementAndGet()
        handler.removeCallbacksAndMessages(recoveryToken)
    }

    // Short, evidence-triggered checks only; no permanent hierarchy polling.
    private fun scheduleRecovery(generation: Int) {
        handler.removeCallbacksAndMessages(recoveryToken)
        handler.postAtTime({
            if (generation == guardGeneration.get() && connected) {
                try { guardWorker.execute {
                    if (generation == guardGeneration.get() && connected &&
                        store.observing && store.blocking && pendingLabel == null) {
                        inspectForSwipe(null)
                    }
                } } catch (_: RejectedExecutionException) { /* New X events can recheck. */ }
            }
        }, recoveryToken, SystemClock.uptimeMillis() + 250)
    }

    private fun queueInspection(event: AccessibilityEvent) {
        val content = event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (content && !contentQueued.compareAndSet(false, true)) return
        val task = GuardTask(AccessibilityEvent.obtain(event), content, guardGeneration.get())
        try { guardWorker.execute(task) } catch (_: RejectedExecutionException) { task.discard() }
    }

    private inner class GuardTask(val event: AccessibilityEvent, val content: Boolean, val generation: Int) : Runnable {
        private val released = AtomicBoolean(false)
        fun discard() {
            if (released.compareAndSet(false, true)) {
                event.recycle()
                if (content) contentQueued.set(false)
            }
        }
        override fun run() {
            try {
                val age = SystemClock.uptimeMillis() - event.eventTime
                if (!connected || age !in 0..1_500 || generation != guardGeneration.get() || !store.observing || !store.blocking || pendingLabel != null) return
                if (appliedGeneration != generation) {
                    swipePolicy.reset(); lastInspection = -1; appliedGeneration = generation
                }
                inspectForSwipe(event)
            } catch (_: Exception) { resetGuard() }
            finally { discard() }
        }
    }

    private fun inspectForSwipe(event: AccessibilityEvent?) {
        val now = SystemClock.elapsedRealtime()
        val generation = appliedGeneration
        swipePolicy.recover(ViewerState(-1, false, false), now) // Expire old evidence, never navigate.
        val isScroll = event?.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (event != null && !isScroll && lastInspection >= 0 && now - lastInspection < 500) return
        lastInspection = now
        try {
            val root = rootInActiveWindow ?: run {
                swipePolicy.observe(ViewerState(-1, false, false), now); return
            }
            try {
                if (root.packageName?.toString() != X_PACKAGE || (event != null && root.windowId != event.windowId)) {
                    swipePolicy.reset(); return
                }
                val viewport = Rect().also { root.getBoundsInScreen(it) }
                // This first experiment is limited to the exact phone, Android/X version,
                // and portrait viewport represented in the saved evidence.
                if (!supportedBlockingBuild || viewport != Rect(0, 0, 720, 1600)) {
                    swipePolicy.reset(); return
                }
                val state = inspectViewer(root, viewport)
                if (isScroll && event != null) {
                    val source = event.source
                    if (source != null) try {
                        val bounds = Rect().also { source.getBoundsInScreen(it) }
                        val sourceMatches = source.packageName?.toString() == X_PACKAGE &&
                            source.windowId == root.windowId && bounds == viewport
                        swipePolicy.remember(PageScroll(event.windowId, sourceMatches,
                            source.isScrollable, source.className?.toString() ?: "", viewport.height(),
                            event.scrollX, event.maxScrollX, event.scrollY, event.maxScrollY), now)
                    } finally { source.recycle() }
                }
                swipePolicy.observe(state, now)
                val consumed = swipePolicy.recover(state, now)
                if (consumed) {
                    // Synchronous check immediately before the only navigation call.
                    val active = rootInActiveWindow ?: return
                    try {
                        if (!connected || (event != null && SystemClock.uptimeMillis() - event.eventTime !in 0..2_000) || appliedGeneration != guardGeneration.get() ||
                            !store.observing || !store.blocking || pendingLabel != null ||
                            active.packageName?.toString() != X_PACKAGE || active.windowId != root.windowId ||
                            !supportedBlockingBuild) return
                        val activeBounds = Rect().also { active.getBoundsInScreen(it) }
                        if (activeBounds != viewport || !inspectViewer(active, activeBounds).viewer) return
                        val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
                        store.recordBlockAttempt(accepted)
                        handler.post {
                            if (connected) Toast.makeText(this, if (accepted) "Back requested after video swipe" else "Could not leave the video feed",
                                Toast.LENGTH_SHORT).show()
                        }
                    } finally { active.recycle() }
                }
            } finally { root.recycle() }
        } catch (_: Exception) {
            // Invalidate the baseline without releasing an already-consumed attempt.
            swipePolicy.observe(ViewerState(-1, false, false), now)
        } finally {
            if (swipePolicy.hasPending && generation == guardGeneration.get()) scheduleRecovery(generation)
        }
    }

    private fun inspectViewer(root: AccessibilityNodeInfo, viewport: Rect): ViewerState {
        var visited = 0
        var complete = true
        var seekBars = 0
        var fullScrolls = 0
        var tallScrolls = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= 160 || depth > 25) { complete = false; return }
            visited++
            if (node.packageName?.toString() != X_PACKAGE || !node.isVisibleToUser) return
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (node.className?.toString() == "android.widget.SeekBar" &&
                bounds == Rect(0, 1492, 720, 1530)) seekBars++
            if (node.isScrollable) {
                if (bounds == viewport && node.childCount == 2) fullScrolls++
                if (bounds.width() == viewport.width() && bounds.height() >= viewport.height() * 9 / 10) tallScrolls++
            }
            for (i in 0 until node.childCount) {
                if (visited >= 160) { complete = false; break }
                val child = node.getChild(i)
                if (child == null) { complete = false; continue }
                try { walk(child, depth + 1) } finally { child.recycle() }
            }
        }
        walk(root, 0)
        return ViewerState(root.windowId, complete && seekBars == 1 && fullScrolls == 1 && tallScrolls == 1,
            complete && seekBars == 0 && tallScrolls >= 2)
    }

    fun scheduleTransition(): Boolean {
        if (!store.observing || pendingLabel != null) return false
        resetGuard()
        pendingLabel = "transition"
        traceStart = SystemClock.elapsedRealtime()
        traceWallStart = System.currentTimeMillis()
        captureDeadline = traceStart + TransitionPlan.DURATION_MS
        traceEvents = JSONArray()
        traceSnapshots = JSONArray()
        traceBudget = TraceBudget()
        skippedSnapshots = 0
        eventCounts.clear()
        store.result = "Transition capture started"
        val scheduleStart = SystemClock.uptimeMillis()
        fun prompt(after: Long, message: String) {
            handler.postAtTime({
                if (pendingLabel == "transition") Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }, traceToken, scheduleStart + after)
        }
        prompt(8_000, "X Feed Guard: open one video. Let it play.")
        prompt(18_000, "X Feed Guard: swipe to the next video once, then wait.")
        prompt(30_000, "X Feed Guard: return to the timeline.")
        val tick = object : Runnable {
            override fun run() {
                if (pendingLabel != "transition") return
                val elapsed = SystemClock.elapsedRealtime() - traceStart
                if (elapsed >= TransitionPlan.DURATION_MS) { finishTransition(); return }
                captureTraceSnapshot(elapsed)
                handler.postAtTime(this, traceToken, SystemClock.uptimeMillis() + TransitionPlan.SNAPSHOT_MS)
            }
        }
        handler.postAtTime(tick, traceToken, scheduleStart + TransitionPlan.SNAPSHOT_MS)
        handler.postAtTime({ if (pendingLabel == "transition") finishTransition() }, traceToken,
            scheduleStart + TransitionPlan.DURATION_MS)
        return true
    }

    private fun recordTraceEvent(event: AccessibilityEvent) {
        val elapsed = SystemClock.elapsedRealtime() - traceStart
        if (elapsed >= TransitionPlan.DURATION_MS) { finishTransition(); return }
        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window"
            else -> "content"
        }
        if (!traceBudget.allow(type, elapsed)) return
        val record = JSONObject().apply {
            put("elapsedMs", elapsed)
            put("guidedPhase", TransitionPlan.phaseAt(elapsed))
            put("type", type)
            put("class", safeClass(event.className?.toString() ?: ""))
            put("windowId", event.windowId)
            put("contentChangeTypes", event.contentChangeTypes)
        }
        if (type == "scroll") {
            record.put("fromIndex", event.fromIndex).put("toIndex", event.toIndex).put("itemCount", event.itemCount)
                .put("scrollX", event.scrollX).put("scrollY", event.scrollY)
                .put("maxScrollX", event.maxScrollX).put("maxScrollY", event.maxScrollY)
            if (Build.VERSION.SDK_INT >= 28) {
                record.put("deltaX", event.scrollDeltaX).put("deltaY", event.scrollDeltaY)
            }
            try {
                val source = event.source
                if (source != null) try {
                    if (source.packageName?.toString() == X_PACKAGE) {
                        val bounds = Rect().also { source.getBoundsInScreen(it) }
                        record.put("sourceClass", safeClass(source.className?.toString() ?: ""))
                        record.put("sourceBounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                        record.put("sourceScrollable", source.isScrollable)
                    }
                } finally { source.recycle() }
            } catch (_: Exception) { record.put("sourceUnavailable", true) }
        }
        traceEvents.put(record)
    }

    private fun captureTraceSnapshot(elapsed: Long) {
        if (traceSnapshots.length() >= 20) return
        try {
            val root = rootInActiveWindow
            if (root == null) { skippedSnapshots++; return }
            try {
                if (root.packageName?.toString() != X_PACKAGE) { skippedSnapshots++; return }
                traceSnapshots.put(snapshot(root, "transition").apply {
                    put("elapsedMs", elapsed)
                    put("guidedPhase", TransitionPlan.phaseAt(elapsed))
                })
            } finally { root.recycle() }
        } catch (_: Exception) { skippedSnapshots++ }
    }

    private fun finishTransition() {
        val session = JSONObject().apply {
            put("traceSchemaVersion", 1)
            put("startedAt", traceWallStart)
            put("durationMs", SystemClock.elapsedRealtime() - traceStart)
            put("requestedDurationMs", TransitionPlan.DURATION_MS)
            put("guidedPhasesAreNotPredictions", true)
            put("events", traceEvents)
            put("snapshots", traceSnapshots)
            put("droppedEvents", traceBudget.dropped)
            put("skippedSnapshots", skippedSnapshots)
        }
        val useful = traceEvents.length() > 0 || traceSnapshots.length() > 0
        cancelCapture()
        try {
            if (useful) {
                store.saveTrace(session)
                store.result = "Transition saved. Return here to export it."
            } else store.result = "No X activity captured. Try again with X open."
        } catch (_: Exception) { store.result = "Transition could not be saved. Try again." }
        Toast.makeText(this, store.result, Toast.LENGTH_LONG).show()
    }

    private fun safeClass(value: String): String = value.takeIf { Regex("[A-Za-z0-9_.$]+").matches(it) } ?: ""

    fun scheduleCapture(label: String): Boolean {
        if (!store.observing || pendingLabel != null || label !in setOf("timeline", "single_video", "video_feed")) return false
        resetGuard()
        pendingLabel = label
        eventCounts.clear()
        captureDeadline = SystemClock.elapsedRealtime() + 12_000L
        store.result = "Sample scheduled"
        captureTask = Runnable {
            try {
                if (!store.observing) { store.result = "Sample cancelled"; return@Runnable }
                val root = rootInActiveWindow
                if (root == null) {
                    store.result = "No accessible screen. Try again with X open."
                } else {
                    try {
                        if (root.packageName?.toString() != X_PACKAGE) {
                            store.result = "X was not in front. No sample saved."
                        } else {
                            val sample = snapshot(root, label)
                            if (sample.getJSONArray("nodes").length() == 0) {
                                store.result = "X exposed no visible elements. No sample saved."
                            } else {
                                store.save(sample)
                                store.result = "${displayLabel(label)} sample saved"
                            }
                        }
                    } finally { root.recycle() }
                }
            } catch (_: Exception) {
                store.result = "Could not capture this screen. Try again."
            } finally {
                pendingLabel = null
                captureDeadline = 0
                captureTask = null
            }
        }
        handler.postDelayed(captureTask!!, 12_000L)
        return true
    }

    fun cancelCapture() {
        resetGuard()
        handler.removeCallbacksAndMessages(traceToken)
        captureTask?.let { handler.removeCallbacks(it) }
        if (pendingLabel != null && ::store.isInitialized) store.result = "Sample cancelled"
        captureTask = null
        pendingLabel = null
        captureDeadline = 0
        traceStart = 0
        traceEvents = JSONArray()
        traceSnapshots = JSONArray()
    }

    private fun snapshot(root: AccessibilityNodeInfo, label: String): JSONObject {
        val nodes = JSONArray()
        val screen = Rect().also { root.getBoundsInScreen(it) }
        var visited = 0
        var truncated = false
        val idPattern = Regex("(?:com\\.twitter\\.android|android):id/[A-Za-z0-9_]+")
        val classPattern = Regex("[A-Za-z0-9_.$]+")
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= 300 || depth > 30) { truncated = true; return }
            visited++
            // Do not inspect child windows belonging to another package.
            if (node.packageName?.toString() != X_PACKAGE) return
            if (node.isVisibleToUser) {
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val rawId = node.viewIdResourceName ?: ""
                val rawClass = node.className?.toString() ?: ""
                nodes.put(JSONObject().apply {
                    put("depth", depth)
                    put("id", rawId.takeIf { idPattern.matches(it) } ?: "")
                    put("class", rawClass.takeIf { classPattern.matches(it) } ?: "")
                    put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                    put("scrollable", node.isScrollable)
                    put("clickable", node.isClickable)
                    put("selected", node.isSelected)
                    put("children", node.childCount)
                })
            }
            for (i in 0 until node.childCount) {
                if (visited >= 300) { truncated = true; break }
                val child = node.getChild(i) ?: continue
                try { walk(child, depth + 1) } finally { child.recycle() }
            }
        }
        walk(root, 0)
        val version = try { packageManager.getPackageInfo(X_PACKAGE, 0).versionName ?: "unknown" }
            catch (_: Exception) { "unknown" }
        return JSONObject().apply {
            put("label", label) // A user label, not a detector classification.
            put("capturedAt", System.currentTimeMillis())
            put("androidSdk", Build.VERSION.SDK_INT)
            put("androidVersion", Build.VERSION.RELEASE)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("xVersion", version)
            put("screenBounds", JSONArray(listOf(screen.left, screen.top, screen.right, screen.bottom)))
            put("eventsDuringCapture", JSONObject(eventCounts.toMap()))
            put("truncated", truncated)
            put("nodes", nodes)
        }
    }

    override fun onInterrupt() { cancelCapture() }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        accessibilityButtonController.unregisterAccessibilityButtonCallback(shortcutCallback)
        cancelCapture()
        reference.clear()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        connected = false
        accessibilityButtonController.unregisterAccessibilityButtonCallback(shortcutCallback)
        guardWorker.shutdownNow().forEach { (it as? GuardTask)?.discard() }
        cancelCapture()
        reference.clear()
        super.onDestroy()
    }
}

fun displayLabel(label: String): String = when (label) {
    "timeline" -> "Timeline"
    "single_video" -> "Single video"
    "video_feed" -> "Video feed"
    "transition" -> "Video transition"
    else -> "Unknown"
}
