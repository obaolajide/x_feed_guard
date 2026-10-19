package app.xfeedguard

enum class ProtectionStatus(val title: String, val detail: String) {
    PAUSED("Protection paused", "Tap to open the on/off controls"),
    ACCESS_NEEDED("Accessibility needs attention", "Open controls and reconnect accessibility"),
    UNSUPPORTED("Protection unavailable", "This phone or X version has not been validated"),
    RECORDING("Recording a sample", "Blocking is paused while recording"),
    ACTIVE("X Feed Guard is running", "Protection on in the tested portrait layout; tap for controls")
}

fun protectionStatus(requested: Boolean, observing: Boolean, connected: Boolean, recording: Boolean, supported: Boolean = true): ProtectionStatus = when {
    !requested || !observing -> ProtectionStatus.PAUSED
    !connected -> ProtectionStatus.ACCESS_NEEDED
    !supported -> ProtectionStatus.UNSUPPORTED
    recording -> ProtectionStatus.RECORDING
    else -> ProtectionStatus.ACTIVE
}
