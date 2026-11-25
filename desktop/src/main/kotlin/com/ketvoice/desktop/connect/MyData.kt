package com.ketvoice.desktop.connect

import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.account.SupabaseHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * « Mes données »: the account's data as one JSON file (the `my-data` edge function gathers the listener's own rows
 * with their own session) and the deletion of the account (the same function, with the service role on the server,
 * after the listener typed [CONFIRM]; it refuses the publishing account).
 */
object MyData {
    /** What the listener types to delete their account; the server checks the same word. */
    const val CONFIRM = "SUPPRIMER"
    private const val PATH = "/functions/v1/my-data"

    sealed interface Result {
        data class Ok(val file: File) : Result
        data class Failed(val message: String) : Result
    }

    /** Writes the export to [target] (its folder made); local settings of this PC are added under `this_pc`. */
    suspend fun export(target: File): Result = withContext(Dispatchers.IO) {
        if (AccountSession.userId() == null) return@withContext Result.Failed("Connectez-vous à votre compte Ketvoice.")
        val r = runCatching { AccountSession.authorized { t -> SupabaseHttp.request("GET", "$PATH/export", token = t) } }.getOrNull()
            ?: return@withContext Result.Failed("Pas de connexion. Réessayez plus tard.")
        val json = runCatching { JSONObject(r.text) }.getOrNull()
        if (!r.ok || json == null) return@withContext Result.Failed("Export impossible pour le moment.")
        json.put("this_pc", thisPc())
        target.parentFile?.mkdirs()
        target.writeText(json.toString(1))
        Result.Ok(target)
    }

    /** What this PC keeps about the account's connections (names and switches, never a token). */
    internal fun thisPc(): JSONObject = JSONObject()
        .put("discord_status", ConnectPrefs.discord.value).put("discord_hide_title", ConnectPrefs.discordHideTitle.value)
        .put("live_sync", ConnectPrefs.liveSync.value)
        .put("lastfm_user", ConnectPrefs.lastfmUser.value).put("listenbrainz_user", ConnectPrefs.listenBrainzUser.value)
        .put("scrobbles_waiting", JSONObject().put("lastfm", Scrobbler.pending("lastfm")).put("listenbrainz", Scrobbler.pending("listenbrainz")))

    /** The default place of the export: the Documents folder (or home), named by the date. */
    fun defaultFile(now: java.time.LocalDate = java.time.LocalDate.now()): File {
        val home = File(System.getProperty("user.home"))
        val docs = File(home, "Documents").takeIf { it.isDirectory } ?: home
        return File(docs, "ketvoice-mes-donnees-$now.json")
    }

    /** True when [typed] is the confirmation word (any case, spaces around ignored). */
    fun confirmed(typed: String): Boolean = typed.trim().equals(CONFIRM, ignoreCase = true)

    /** Deletes the account for good; on success this PC forgets it too. */
    suspend fun deleteAccount(typed: String): Result = withContext(Dispatchers.IO) {
        if (!confirmed(typed)) return@withContext Result.Failed("Tapez SUPPRIMER pour confirmer.")
        if (AccountSession.userId() == null) return@withContext Result.Failed("Connectez-vous à votre compte Ketvoice.")
        val body = JSONObject().put("confirm", CONFIRM).toString().toByteArray()
        val r = runCatching { AccountSession.authorized { t -> SupabaseHttp.request("POST", "$PATH/delete", body, t) } }.getOrNull()
            ?: return@withContext Result.Failed("Pas de connexion. Réessayez plus tard.")
        when {
            r.ok -> {
                ConnectPrefs.setListenBrainzToken(null)
                ConnectPrefs.setText(ConnectPrefs.lastfmUser, "lastfmUser", "")
                AccountSession.endedOnServer("Votre compte a été supprimé.")
                Result.Ok(File(""))
            }
            r.status == 403 -> Result.Failed("Ce compte ne peut pas être supprimé ici.")
            else -> Result.Failed("Suppression impossible pour le moment.")
        }
    }
}
