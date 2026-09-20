package com.muedsa.snapshot.open

import io.ktor.server.application.Application

fun Application.configureFonts() {
    val families = snapshotConfig().fontFamilyNames
    if (families.isNotEmpty()) {
        FontService.setDefaultFamilyNames(families)
    }
}
