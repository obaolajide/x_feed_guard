package app.xfeedguard

fun testProtectionStatus() {
    check(protectionStatus(false, false, false, false) == ProtectionStatus.PAUSED)
    check(protectionStatus(false, true, true, false) == ProtectionStatus.PAUSED)
    check(protectionStatus(true, false, true, false) == ProtectionStatus.PAUSED)
    check(protectionStatus(true, true, false, false) == ProtectionStatus.ACCESS_NEEDED)
    check(protectionStatus(true, true, false, true) == ProtectionStatus.ACCESS_NEEDED)
    check(protectionStatus(true, true, true, false, false) == ProtectionStatus.UNSUPPORTED)
    check(protectionStatus(true, true, true, true) == ProtectionStatus.RECORDING)
    check(protectionStatus(true, true, true, false) == ProtectionStatus.ACTIVE)
    println("PASS: notification never calls disconnected, unsupported, paused, or recording protection active")
}
