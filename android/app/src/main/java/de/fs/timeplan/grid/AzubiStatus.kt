package de.fs.timeplan.grid

import de.fs.timeplan.R

// Single source of truth for the fixed status vocabulary — the label is also
// the literal string stored via DemoApi.putEntry, so it must not be duplicated
// as a separate string resource (that drifted out of sync once already).
enum class AzubiStatus(val label: String, val chipBackground: Int) {
    SCHULE("Schule", R.drawable.bg_chip_status_school),
    KRANK("Krank", R.drawable.bg_chip_status_sick),
    URLAUB("Urlaub", R.drawable.bg_chip_status_vacation);

    companion object {
        fun from(text: String?): AzubiStatus? = entries.firstOrNull { it.label == text }
    }
}
