package com.ketvoice.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.ketvoice.app.ui.common.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.AppRoot
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.util.Crash

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.ketvoice.app.data.AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // d'abord : trappeur de crash (avant toute initialisation)
        Crash.install(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // réglages chargés AVANT l'init du cache audio / du player
        runCatching { com.ketvoice.app.data.SettingsStore.load(this) }
            .onFailure { android.util.Log.e("KetvoiceSettings", "Could not restore settings", it) }
        runCatching { com.ketvoice.app.data.YouTubeMusicSession.init(this) }
        runCatching { com.ketvoice.app.data.PublisherSession.init(this) }
        runCatching { com.ketvoice.app.data.CustomSources.init(this) }
        runCatching {
            com.ketvoice.app.util.LauncherIcons.apply(
                this,
                com.ketvoice.app.data.AppSettings.appIconVariant.value
            )
        }

        try {
            Player.connect(applicationContext)
        } catch (_: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4123
            )
        }

        setContent {
            val view = LocalView.current
            val playback by Player.state.collectAsState()
            val keepAwake = BuildConfig.IS_QUEST && com.ketvoice.app.data.AppSettings.questKeepAwake.value && playback.playWhenReady
            DisposableEffect(view, keepAwake) {
                view.keepScreenOn = keepAwake
                onDispose { view.keepScreenOn = false }
            }
            val dark = isSystemInDarkTheme()
            // AppRoot owns bar contrast, including the always-dark player.
            KetvoiceTheme(dark) {
                CrashReportsDialog()
                AppRoot()
            }
        }
        handleAuthIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
    }

    /**
     * Email links either point at Supabase's /auth/v1/verify endpoint (the app
     * can verify the token itself) or at ketvoice://auth (Supabase already
     * verified it and appended the session). Both open the publisher screen;
     * the server remains the only authority for publication rights.
     */
    private fun handleAuthIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val supabaseLink = uri.scheme == "https" &&
            uri.host == com.ketvoice.app.data.PublisherAccess.HOST &&
            uri.path == "/auth/v1/verify"
        val appLink = uri.scheme == "ketvoice" && uri.host == "auth"
        if (supabaseLink || appLink) {
            AppUi.openPublisherLink(uri.toString())
            // Do not retain bearer credentials in the Activity's launch Intent
            // or replay a one-use email link when the Activity is recreated.
            intent.data = null
        }
    }

    override fun onStop() {
        super.onStop()
        runCatching { com.ketvoice.app.data.SettingsStore.save() }
    }
}

/**
 * Si un crash s'est produit depuis la dernière session : affiche le rapport
 * (copiable en un appui) au lieu de laisser l'utilisateur dans le noir.
 */
@Composable
private fun CrashReportsDialog() {
    val activity = androidx.activity.compose.LocalActivity.current ?: return
    val pending = remember { Crash.pending(activity) }
    var index by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(pending.isNotEmpty()) }
    if (!visible || pending.isEmpty()) return
    val report = remember(index) {
        runCatching { pending[index].readText() }.getOrDefault("(rapport illisible)")
    }
    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text("Rapport de crash") },
        text = {
            Text(
                report,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ketvoice_crash", report))
                        Toast.makeText(activity, com.ketvoice.app.data.AppText.t("Copié — colle-le dans la conversation"), Toast.LENGTH_SHORT).show()
                }
                Crash.clearAll(activity)
                visible = false
            }) { Text("Copier le rapport") }
        },
        dismissButton = {
            TextButton(onClick = { Crash.clearAll(activity); visible = false }) { Text("Effacer") }
        }
    )
}
