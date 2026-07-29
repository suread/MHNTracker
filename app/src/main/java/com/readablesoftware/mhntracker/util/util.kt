package com.readablesoftware.mhntracker.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Use java.timeDateTimeFormatter for thread-safe date formatting // TODO remove SimpleDateFormat so all formatting is thread-safe by default
object ExportTimestamps {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.UK)

    fun format(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.systemDefault())
            .format(formatter)

    // parse is currently only exercised by tests
    fun parse(timestamp: String): Long =
        LocalDateTime.parse(timestamp, formatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}

