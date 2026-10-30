package com.openminis.app.ui.chat

import android.content.Context
import java.io.InputStream

/** Loads prompt text from packaged UTF-8 assets instead of embedding it in app bytecode. */
internal object MinesPrompts {
    const val COMPACT_SUMMARY = "mines-prompts/compact-summary.txt"

    fun compactSummary(context: Context): String =
        context.assets.open(COMPACT_SUMMARY).use { read(it) }

    internal fun read(input: InputStream): String =
        input.bufferedReader(Charsets.UTF_8).use { it.readText().trimEnd('\r', '\n') }
}
