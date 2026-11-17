package com.ketvoice.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ketvoice.app.data.AppText
import com.ketvoice.app.util.Crash
import com.ketvoice.app.util.CrashLoop

/**
 * Safe mode (6.1.1): opened instead of the app after it closed twice in a row at launch. Plain Android views
 * only — none of the app's screens, theme, player or services — so it opens even when they are what crashes.
 * It shows the latest crash report and lets the listener share or copy it, or try the app again.
 */
class SafeModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = runCatching { Crash.pending(this).lastOrNull()?.readText() }.getOrNull()
            ?: AppText.t("Aucun rapport n'a été trouvé.")
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(12, 12, 16))
            setPadding(pad, pad * 2, pad, pad)
        }
        fun text(value: String, size: Float, bold: Boolean = false, alpha: Float = 1f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE); this.alpha = alpha
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, pad / 2)
        }
        root.addView(text(AppText.t("Ketvoice s'est arrêté au démarrage"), 22f, bold = true))
        root.addView(text(AppText.t("Partagez ce rapport avec le développeur : il indique exactement ce qui a planté."), 15f, alpha = .8f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
        }.also { buttons.addView(it) }
        button(AppText.t("Partager le rapport")) {
            runCatching {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Ketvoice crash").putExtra(Intent.EXTRA_TEXT, report), null))
            }
        }
        button(AppText.t("Copier")) {
            runCatching {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ketvoice crash", report))
                Toast.makeText(this, AppText.t("Rapport copié"), Toast.LENGTH_SHORT).show()
            }
        }
        button(AppText.t("Relancer")) {
            runCatching { CrashLoop.reset(this); Crash.clearAll(this) }
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
        root.addView(buttons)
        val log = TextView(this).apply {
            text = report; textSize = 11f; setTextColor(Color.rgb(210, 210, 220)); typeface = Typeface.MONOSPACE
            setTextIsSelectable(true); setPadding(0, pad / 2, 0, pad)
        }
        root.addView(ScrollView(this).apply { addView(log) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }
}
