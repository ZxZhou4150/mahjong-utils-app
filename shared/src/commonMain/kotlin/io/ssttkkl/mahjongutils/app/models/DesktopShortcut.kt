package io.ssttkkl.mahjongutils.app.models

import kotlinx.serialization.Serializable

@Serializable
data class DesktopShortcut(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    fun displayText(): String = buildList {
        if (ctrl) add("Ctrl")
        if (alt) add("Alt")
        if (shift) add("Shift")
        if (meta) add("Meta")
        add(desktopShortcutKeyLabel(key))
    }.joinToString("+")
}

@Serializable
data class DesktopScreenRegionShortcutOptions(
    val startSelection: DesktopShortcut = DesktopShortcut("A", alt = true),
    val recognizeSelection: DesktopShortcut = DesktopShortcut("D", alt = true),
    val exitSelection: DesktopShortcut = DesktopShortcut("Q", alt = true),
) {
    fun hasConflict(): Boolean {
        val shortcuts = listOf(startSelection, recognizeSelection, exitSelection)
        return shortcuts.distinct().size != shortcuts.size
    }

    companion object {
        val Default = DesktopScreenRegionShortcutOptions()
    }
}

data class DesktopShortcutKeyOption(
    val value: String,
    val label: String,
)

private val desktopShortcutKeyOptionsInternal = buildList {
    add(DesktopShortcutKeyOption("ESCAPE", "Esc"))
    add(DesktopShortcutKeyOption("SPACE", "Space"))
    ('A'..'Z').forEach { add(DesktopShortcutKeyOption(it.toString(), it.toString())) }
    ('0'..'9').forEach { add(DesktopShortcutKeyOption(it.toString(), it.toString())) }
    (1..12).forEach { add(DesktopShortcutKeyOption("F$it", "F$it")) }
}

val desktopShortcutKeyOptions: List<DesktopShortcutKeyOption>
    get() = desktopShortcutKeyOptionsInternal

fun desktopShortcutKeyLabel(key: String): String =
    desktopShortcutKeyOptionsInternal.firstOrNull { it.value == key }?.label ?: key
