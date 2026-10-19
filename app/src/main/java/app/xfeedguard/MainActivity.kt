package app.xfeedguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ComponentName
import android.net.Uri
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var store: GuardStore
    private lateinit var home: LinearLayout
    private lateinit var pageScroll: ScrollView
    private lateinit var details: LinearLayout
    private lateinit var detailsButton: Button
    private var countedRevision = Long.MIN_VALUE
    private val sampleCounts = mutableMapOf<String, Int>()
    private var sampleTotal = 0
    private val handler = Handler(Looper.getMainLooper())
    private val ink = Color.BLACK
    private val secondary = Color.rgb(99, 99, 99)
    private val pageColor = Color.WHITE
    private lateinit var accessTitle: TextView
    private lateinit var accessDetail: TextView
    private lateinit var accessButton: Button
    private lateinit var observeSwitch: Switch
    private lateinit var blockSwitch: PowerToggle
    private lateinit var blockStatus: TextView
    private lateinit var repairButton: Button
    private lateinit var notificationButton: Button
    private lateinit var backgroundStatus: TextView
    private lateinit var liveStatus: TextView
    private lateinit var captureStatus: TextView
    private lateinit var exportButton: Button
    private lateinit var cancelButton: Button
    private lateinit var transitionButton: Button
    private lateinit var transitionStatus: TextView
    private val sampleButtons = mutableMapOf<String, Button>()
    private var updating = false
    private val ticker = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GuardStore(this)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = pageColor
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = FrameLayout(this).apply { setBackgroundColor(pageColor) }
        home = column().apply {
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(home, FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this).apply {
            setBackgroundColor(pageColor)
            isFillViewport = true
            visibility = View.GONE
        }
        pageScroll = scroll
        val page = column().apply { setPadding(dp(24), dp(18), dp(24), dp(28)) }
        scroll.addView(page)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            root.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)

        blockSwitch = PowerToggle(this).apply {
            setOnCheckedChangeListener { _, checked ->
                if (!updating) {
                    store.setProtection(checked)
                    ProtectionService.sync(this@MainActivity, store)
                    val service = ObservationService.current
                    if (checked) service?.resetGuard() else service?.cancelCapture()
                    refresh()
                    if (checked && (!accessEnabled() || service == null)) explainAccess()
                }
            }
        }
        home.addView(blockSwitch, LinearLayout.LayoutParams(dp(144), dp(144)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        home.addView(button("X", false) { openX() }.apply {
            textSize = 46f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(ink)
            contentDescription = "Open X"
            setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(72), dp(72)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(24)
        })
        detailsButton = button("Settings & diagnostics", false) {
            home.visibility = View.GONE
            pageScroll.visibility = View.VISIBLE
            pageScroll.scrollTo(0, 0)
            refresh()
        }.apply {
            setTextColor(ink)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        home.addView(detailsButton, LinearLayout.LayoutParams(-2, dp(48)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        page.addView(button("‹ Back", false) { showControls() }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(ink)
            contentDescription = "Back to protection"
        }, fullWidth())
        page.addView(text("Settings & diagnostics", 27f, ink, true).apply { top(16) })
        details = column()
        page.addView(details, fullWidth().apply { topMargin = dp(24) })
        val protection = card()
        protection.addView(text("Protection", 18f, ink, true))
        blockStatus = text("", 14f, secondary).apply { top(10) }
        protection.addView(blockStatus)
        repairButton = button("Reconnect accessibility", true) { openAccessSettings() }
        protection.addView(repairButton, fullWidth().apply { topMargin = dp(12) })
        details.addView(protection, fullWidth().apply { bottomMargin = dp(20) })
        notificationButton = button("Notification settings", false) {
            try {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } catch (_: Exception) { openOwnAppSettings() }
        }
        details.addView(notificationButton, fullWidth().apply { bottomMargin = dp(20) })
        observeSwitch = FullRowSwitch(this).apply {
            text = "Observe X"
            textSize = 19f
            setTextColor(ink)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = dp(56)
            switchPadding = dp(16)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ink, Color.rgb(200, 200, 200)))
            setOnCheckedChangeListener { _, checked ->
                if (!updating) {
                    store.observing = checked
                    if (!checked) { store.blocking = false; ObservationService.current?.cancelCapture() }
                    ProtectionService.sync(this@MainActivity, store)
                    refresh()
                }
            }
        }
        
        liveStatus = text("", 14f, secondary).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        
        details.addView(observeSwitch, fullWidth())
        details.addView(liveStatus, fullWidth().apply { bottomMargin = dp(16) })

        val access = card()
        accessTitle = text("Enable access", 18f, ink, true)
        access.addView(accessTitle)
        accessDetail = text("", 14f, secondary).apply { top(8); setLineSpacing(dp(3).toFloat(), 1f) }
        access.addView(accessDetail)
        accessButton = button("Set up access", true) { explainAccess() }.apply { top(18) }
        access.addView(accessButton, fullWidth().apply { topMargin = dp(18) })
        details.addView(access, fullWidth().apply { bottomMargin = dp(28) })

        val background = card()
        background.addView(text("Keep running", 18f, ink, true))
        background.addView(text("You can close this screen. On your Redmi, allow background autostart and choose No restrictions in the app's battery settings to help prevent cleanup.", 14f, secondary).apply {
            top(8); setLineSpacing(dp(3).toFloat(), 1f)
        })
        backgroundStatus = text("", 13f, secondary).apply { top(12) }
        background.addView(backgroundStatus)
        background.addView(button("Background autostart", true) { openAutostart() }, fullWidth().apply { topMargin = dp(14) })
        background.addView(button("App battery settings", false) { openOwnAppSettings() }, fullWidth().apply { topMargin = dp(6) })
        background.addView(button("Battery optimization list", false) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) { openOwnAppSettings() }
        }, fullWidth().apply { topMargin = dp(6) })
        background.addView(button("Stop background support", false) {
            store.backgroundEnabled = false
            ProtectionService.sync(this@MainActivity, store)
            refresh()
        }, fullWidth().apply { topMargin = dp(6) })
        background.addView(text("The ongoing notification opens these controls and lets you pause or resume. Background support stays available while protection is paused; Stop background support removes it.", 12f, secondary).apply { top(10) })
        background.addView(text("Android controls accessibility access. If Settings reports that the service is not working, turn X Feed Guard off and back on there. Force stop or phone cleanup can still interrupt it.", 12f, secondary).apply {
            top(12); setLineSpacing(dp(3).toFloat(), 1f)
        })
        details.addView(background, fullWidth().apply { bottomMargin = dp(28) })

        details.addView(text("Watch the transition", 20f, ink, true))
        details.addView(text("A guided 40-second test, from the timeline to a video, one swipe, and back.", 14f, secondary).apply {
            top(8); setLineSpacing(dp(4).toFloat(), 1f)
        })
        transitionButton = button("Record a video transition", true) { captureTransition() }
        details.addView(transitionButton, fullWidth().apply { topMargin = dp(16) })
        transitionStatus = text("", 13f, secondary).apply { top(10) }
        details.addView(transitionStatus)

        details.addView(text("Individual screens", 20f, ink, true).apply { top(26) })
        details.addView(text("Choose a screen, then open it in X within 12 seconds. Return here after the capture.", 14f, secondary).apply {
            top(8); setLineSpacing(dp(4).toFloat(), 1f)
        })
        val samples = card().apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        for ((index, label) in listOf("timeline", "single_video", "video_feed").withIndex()) {
            if (index > 0) samples.addView(View(this).apply { setBackgroundColor(pageColor) }, fullWidth(dp(1)))
            val row = button(displayLabel(label), false) { capture(label) }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            sampleButtons[label] = row
            samples.addView(row, fullWidth(dp(58)))
        }
        details.addView(samples, fullWidth().apply { topMargin = dp(16) })
        captureStatus = text("No samples yet", 13f, secondary).apply { top(12) }
        details.addView(captureStatus)
        cancelButton = button("Cancel capture", false) { ObservationService.current?.cancelCapture(); refresh() }
        details.addView(cancelButton, fullWidth().apply { topMargin = dp(6) })

        exportButton = button("Export samples", true) { export() }
        details.addView(exportButton, fullWidth().apply { topMargin = dp(18) })
        details.addView(button("Delete samples", false) {
            AlertDialog.Builder(this).setTitle("Delete all samples?")
                .setMessage("This removes the screen samples and transition recordings stored on this phone.")
                .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                    ObservationService.current?.cancelCapture(); store.clear(); refresh()
                }.show()
        }, fullWidth().apply { topMargin = dp(6) })
        details.addView(text("Private by design", 14f, ink, true).apply { top(26); gravity = Gravity.CENTER })
        details.addView(text("Screen structure stays on your phone.\nNo post text, messages, or screenshots are saved.", 12f, secondary).apply {
            top(7); gravity = Gravity.CENTER; setLineSpacing(dp(3).toFloat(), 1f)
        })
        details.addView(text("PROTOTYPE 0.7.0", 10f, secondary).apply { top(22); gravity = Gravity.CENTER; letterSpacing = 0.12f })
        refresh()
    }

    private fun openX() {
        val launch = packageManager.getLaunchIntentForPackage(ObservationService.X_PACKAGE)
        if (launch == null) toast("The official X app is not installed.")
        else try { startActivity(launch) } catch (_: Exception) { toast("Could not open X.") }
    }

    @Deprecated("Uses platform Activity without additional runtime dependencies")
    override fun onBackPressed() {
        if (pageScroll.visibility == View.VISIBLE) showControls() else super.onBackPressed()
    }

    private fun accessEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == ObservationService::class.java.name
        }
    }

    private fun refresh() {
        val permitted = accessEnabled()
        val service = ObservationService.current
        updating = true
        observeSwitch.isChecked = store.observing
        observeSwitch.isEnabled = true // The user can pause even while access is disconnected.
        val supported = service?.supportedBlockingBuild == true
        // Keep settings targets stationary during Android-managed reconnects and UI inspection.
        repairButton.visibility = if (!permitted || service == null) View.VISIBLE else View.INVISIBLE
        blockSwitch.isChecked = store.blocking
        blockSwitch.isEnabled = true // A requested on/off choice always responds; access is shown separately.
        blockSwitch.alpha = if (blockSwitch.isEnabled) 1f else 0.45f
        updating = false
        accessTitle.text = if (permitted && service != null) "Access is ready" else if (permitted) "Waiting for connection" else "Enable access"
        accessDetail.text = if (permitted && service != null) "X Feed Guard can observe X. You can revoke access at any time in Settings."
            else if (permitted) "Android lists access as enabled, but the service is disconnected. If it stays this way, turn X Feed Guard off and back on in Accessibility settings. Your blocking choice is kept."
            else "Allow X Feed Guard in Accessibility settings. Android requires you to turn this on yourself."
        accessButton.text = if (permitted) "Accessibility settings" else "Set up access"
        liveStatus.text = when {
            !permitted -> "Accessibility access needed"
            service == null -> "Waiting for the accessibility service"
            !store.observing -> "Paused. Turn on when you are ready."
            service.lastEventAt == 0L -> "Ready. Open X to begin observing."
            else -> "${service.eventTotal} X events this session · ${store.blockAttempts} Back attempts"
        }
        val pending = service?.pendingLabel != null
        blockStatus.text = when {
            store.blocking && (!permitted || service == null) -> "Choice saved · waiting for accessibility access"
            store.blocking && !store.observing -> "Choice saved · observation paused"
            service != null && !supported -> "Blocking unavailable on this phone or X version. Tested with your Redmi 13C, Android 15, X 12.35.0."
            pending -> "Blocking paused while recording"
            store.blocking -> "On · Connected · ${store.blockStatus}"
            else -> "Off · Tap the power button to turn on"
        }
        val power = getSystemService(POWER_SERVICE) as PowerManager
        backgroundStatus.text = (if (ProtectionService.running) "Background support is running. " else "Background support is stopped. ") +
            (if (power.isIgnoringBatteryOptimizations(packageName))
            "Android battery optimization: unrestricted. Check Xiaomi autostart separately."
            else "Android battery optimization is enabled. Xiaomi background settings are managed separately.") +
            (if (store.backgroundError.isNotEmpty()) "\n${store.backgroundError}" else "")
        transitionButton.isEnabled = permitted && service != null && store.observing && !pending
        transitionButton.alpha = if (transitionButton.isEnabled) 1f else 0.45f
        transitionStatus.text = if (store.traceCount > 0) "${store.traceCount} transition(s) saved · latest 3 kept"
            else "Text prompts guide you. No screen video is recorded."
        if (countedRevision != store.sampleRevision) {
            val allSamples = store.samples()
            sampleTotal = allSamples.length()
            sampleCounts.clear()
            for (i in 0 until allSamples.length()) {
                val label = allSamples.getJSONObject(i).optString("label")
                sampleCounts[label] = (sampleCounts[label] ?: 0) + 1
            }
            countedRevision = store.sampleRevision
        }
        for ((label, button) in sampleButtons) {
            val count = sampleCounts[label] ?: 0
            button.text = "${displayLabel(label)}${if (count > 0) "   ·   $count saved" else ""}"
            button.isEnabled = permitted && service != null && store.observing && !pending
            button.alpha = if (button.isEnabled) 1f else 0.45f
        }
        captureStatus.text = if (pending) {
            val seconds = ((service!!.captureDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 999) / 1000
            if (service.pendingLabel == "transition") "Recording transition · ${seconds}s left"
            else "${displayLabel(service.pendingLabel!!)} · capturing in ${seconds}s"
        } else store.result
        cancelButton.visibility = if (pending) View.VISIBLE else View.GONE
        exportButton.isEnabled = sampleTotal > 0 || store.traceCount > 0
        exportButton.alpha = if (exportButton.isEnabled) 1f else 0.45f
    }

    private fun explainAccess() {
        if (accessEnabled()) { openAccessSettings(); return }
        AlertDialog.Builder(this).setTitle("Allow access to X")
            .setMessage("Android grants this service access to exposed screen elements. X Feed Guard observes X and captures structure when you ask. If you enable experimental blocking, it presses Back after a matching video swipe. It never saves post text or messages, records keystrokes, or sends data online.\n\nIn Settings, choose X Feed Guard and enable it. You can turn it off there at any time.")
            .setNegativeButton("Not now", null).setPositiveButton("Open Settings") { _, _ -> openAccessSettings() }.show()
    }

    private fun openAccessSettings() {
        try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        catch (_: Exception) { toast("Open Settings → Accessibility → Downloaded apps → X Feed Guard") }
    }

    private fun openOwnAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) { toast("Open Settings → Apps → X Feed Guard → Battery. Choose No restrictions if available.") }
    }

    private fun openAutostart() {
        try {
            startActivity(Intent().setComponent(ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity")))
        } catch (_: Exception) {
            toast("Open Settings → Apps → Permissions → Background autostart, then enable X Feed Guard.")
            openOwnAppSettings()
        }
    }

    private fun capture(label: String) {
        val instructions = when (label) {
            "timeline" -> "Stay on your normal X timeline."
            "single_video" -> "Open one video from your timeline. Do not swipe to another video."
            else -> "Open a full-screen video, then swipe to another video. Stay in that feed."
        }
        AlertDialog.Builder(this).setTitle(displayLabel(label))
            .setMessage("$instructions\n\nX will open. You have 12 seconds to reach the screen. Then return here to check the result. Only screen structure is saved.")
            .setNegativeButton("Cancel", null).setPositiveButton("Open X and capture") { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage(ObservationService.X_PACKAGE)
                if (launch == null) { toast("The official X app is not installed."); return@setPositiveButton }
                val service = ObservationService.current
                if (service?.scheduleCapture(label) != true) { toast("Turn on observation and check accessibility access."); return@setPositiveButton }
                try { startActivity(launch) }
                catch (_: Exception) { service.cancelCapture(); toast("Could not open X.") }
                refresh()
            }.show()
    }

    private fun captureTransition() {
        AlertDialog.Builder(this).setTitle("One video, then one swipe")
            .setMessage("X will open and a 40-second capture will begin. Keep the phone unlocked.\n\n0–8 seconds: return to your normal timeline.\n8–18 seconds: open one video and let it play.\n18–30 seconds: swipe to the next video once, then wait.\n30–40 seconds: return to the timeline.\n\nShort text prompts guide you. Only screen structure and scroll-event metadata are saved. You can return here and cancel at any time.")
            .setNegativeButton("Cancel", null).setPositiveButton("Open X and start") { _, _ ->
                val launch = packageManager.getLaunchIntentForPackage(ObservationService.X_PACKAGE)
                if (launch == null) { toast("The official X app is not installed."); return@setPositiveButton }
                val service = ObservationService.current
                if (service?.scheduleTransition() != true) { toast("Turn on observation and check accessibility access."); return@setPositiveButton }
                try { startActivity(launch) }
                catch (_: Exception) { service.cancelCapture(); toast("Could not open X.") }
                refresh()
            }.show()
    }

    private fun export() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "x-feed-guard-samples.json")
        }
        try { startActivityForResult(intent, 41) }
        catch (_: Exception) { toast("No file picker is available on this phone.") }
    }

    @Deprecated("Uses platform Activity to avoid additional runtime dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val output = contentResolver.openOutputStream(uri, "wt") ?: error("No output stream")
            output.use { it.write(JSONObject().apply {
                put("schemaVersion", 1)
                put("appVersion", packageManager.getPackageInfo(packageName, 0).versionName)
                put("mode", "structural_samples")
                put("labelsAreUserSupplied", true)
                put("samples", store.samples())
                put("transitions", store.traces())
            }.toString(2).toByteArray(Charsets.UTF_8)) }
            toast("Samples exported")
        } catch (_: Exception) { toast("Export failed. Choose a different location and try again.") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showControls()
    }
    private fun showControls() {
        pageScroll.visibility = View.GONE
        home.visibility = View.VISIBLE
        pageScroll.post { pageScroll.scrollTo(0, 0) }
        refresh()
    }
    override fun onResume() {
        super.onResume()
        ProtectionService.sync(this@MainActivity, store)
        refresh()
        handler.removeCallbacks(ticker); handler.post(ticker)
    }
    override fun onPause() { handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { handler.removeCallbacks(ticker); super.onDestroy() }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun fullWidth(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    private fun View.top(value: Int) {
        layoutParams = fullWidth().apply { topMargin = dp(value) }
    }
    private fun text(value: String, size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun shape(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius.toInt()).toFloat()
    }
    private fun card() = column().apply {
        background = shape(Color.WHITE, 22f)
        setPadding(dp(22), dp(22), dp(22), dp(22))
    }
    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        text = label; textSize = 15f; isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) Color.WHITE else ink)
        minHeight = dp(52); minimumHeight = dp(52)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(35, 0, 0, 0)),
            shape(if (primary) ink else Color.TRANSPARENT, 15f), shape(Color.WHITE, 15f))
        stateListAnimator = null
        setOnClickListener { action() }
    }
}
