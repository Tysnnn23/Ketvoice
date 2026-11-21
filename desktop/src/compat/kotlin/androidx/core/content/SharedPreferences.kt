package androidx.core.content

import android.content.SharedPreferences

/** androidx.core's `edit { }` over the desktop preferences. */
inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val editor = edit()
    action(editor)
    if (commit) editor.commit() else editor.apply()
}
