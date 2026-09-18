package com.muedsa.snapshot.open

import io.ktor.server.application.Application
import io.ktor.server.config.tryGetStringList

fun Application.configureFonts() {
    val families = environment.config.tryGetStringList("snapshot.font-family-names")
    if (!families.isNullOrEmpty()) {
        FontService.setDefaultFamilyNames(families)
    }
}
