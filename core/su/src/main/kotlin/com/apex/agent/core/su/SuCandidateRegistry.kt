package com.apex.agent.core.su

/** Single, ordered source of su paths used by both privilege routing and chroot. */
object SuCandidateRegistry {
    val paths: List<String> = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
    )
}
