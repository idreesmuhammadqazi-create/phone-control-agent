package dev.pocketpilot.core

/**
 * Compact role names. Roles are intentionally short because every character is paid for in
 * the agent's context window.
 */
object Roles {
    const val BUTTON = "btn"
    const val TEXT = "txt"
    const val INPUT = "inp"
    const val IMAGE = "img"
    const val CHECKBOX = "chk"
    const val RADIO = "rad"
    const val SWITCH = "swt"
    const val SLIDER = "sld"
    const val SPINNER = "spn"
    const val TAB = "tab"
    const val ITEM = "item"
    const val LINK = "lnk"
    const val SCREEN = "scr"
    const val LIST = "lst"
    const val DIALOG = "dlg"
    const val PROGRESS = "prg"
    const val WEB = "web"
    const val CONTAINER = "box"

    /**
     * Infers a role from the platform class name plus the accessibility flags. The mapping is
     * deliberately conservative: unknown containers stay "box" and are usually pruned later.
     */
    fun infer(className: String?, clickable: Boolean, editable: Boolean, scrollable: Boolean): String {
        val cn = className ?: return if (clickable) BUTTON else CONTAINER
        return when {
            editable || cn.endsWith("EditText") || cn.endsWith("AutoCompleteTextView") -> INPUT
            cn.endsWith("CheckBox") -> CHECKBOX
            cn.endsWith("RadioButton") -> RADIO
            cn.endsWith("Switch") || cn.endsWith("SwitchCompat") || cn.endsWith("ToggleButton") -> SWITCH
            cn.endsWith("SeekBar") || cn.endsWith("Slider") -> SLIDER
            cn.endsWith("Spinner") -> SPINNER
            cn.contains("TabLayout") || cn.endsWith("TabWidget") -> TAB
            cn.endsWith("WebView") -> WEB
            cn.endsWith("RecyclerView") || cn.endsWith("ListView") || cn.endsWith("GridView") -> LIST
            cn.endsWith("ScrollView") || cn.endsWith("NestedScrollView") -> SCREEN
            cn.endsWith("Button") || cn.endsWith("ImageButton") -> if (clickable) BUTTON else TEXT
            cn.endsWith("TextView") -> if (clickable) BUTTON else TEXT
            cn.endsWith("ImageView") || cn.endsWith("IconView") -> if (clickable) BUTTON else IMAGE
            cn.endsWith("ProgressBar") -> PROGRESS
            cn.endsWith("Dialog") -> DIALOG
            clickable || scrollable -> ITEM
            else -> CONTAINER
        }
    }

    /** Roles that never make sense to show on their own if they carry no label or action. */
    fun isStructural(role: String): Boolean =
        role == CONTAINER || role == IMAGE || role == SCREEN || role == LIST || role == TEXT

    fun isActionable(role: String): Boolean = when (role) {
        BUTTON, INPUT, CHECKBOX, RADIO, SWITCH, SLIDER, SPINNER, TAB, ITEM, LINK -> true
        else -> false
    }
}

object Classes {
    fun shortName(className: String?): String {
        if (className.isNullOrEmpty()) return ""
        val idx = className.lastIndexOf('.')
        return if (idx >= 0) className.substring(idx + 1) else className
    }
}
