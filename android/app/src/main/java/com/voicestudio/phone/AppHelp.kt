package com.voicestudio.phone

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.TextView

internal fun Activity.showUsage() {
    val content = TextView(this).apply {
        text = str(R.string.usage_steps) + "\n\n" + str(R.string.cellular_title) + "\n" + str(R.string.cellular_help)
        textSize = 15f
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding / 2, padding, padding)
        setTextIsSelectable(true)
    }
    AlertDialog.Builder(this).setTitle(str(R.string.usage_title))
        .setView(ScrollView(this).apply { addView(content) })
        .setPositiveButton(str(R.string.ui_close), null)
        .show()
}
