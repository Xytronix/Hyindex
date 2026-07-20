// Copyright 2026 Hyindex. All rights reserved.
package com.hyindex.common.settings


object HytaleVersionDetector {

    data class HytaleVersionInfo(
        val patchline: String,
        val date: String,
        val shortHash: String,
        val fullRevision: String,
        val rawVersion: String,
        val buildNumber: Int = 0,
        val protocolCrc: Long = 0L,
        val branch: String = patchline,
    ) {

        val slug: String get() = "${patchline}_${rawVersion}"


        val displayName: String get() = "$patchline/$rawVersion"
    }
}
