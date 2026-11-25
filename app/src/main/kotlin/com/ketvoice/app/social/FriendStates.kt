package com.ketvoice.app.social

import org.json.JSONArray
import org.json.JSONObject

/** Where the listener stands with someone (the server's `friend` field of a profile view). */
enum class FriendState(val wire: String) {
    None("none"), Outgoing("outgoing"), Incoming("incoming"), Friends("friends"), Self("self");

    companion object {
        fun of(wire: String?): FriendState = entries.firstOrNull { it.wire == wire } ?: None
    }
}

/** What the listener can do about it: each maps to one friend RPC (supabase …120200_social_friend_rpcs.sql). */
enum class FriendAction(val label: String) {
    Ask("Ajouter en ami"), Accept("Accepter"), Decline("Refuser"), Cancel("Annuler la demande"), Remove("Retirer des amis")
}

/** One person in the friends lists: an opaque [ref] (the friendship's own id), never an account id. */
data class FriendLine(val ref: String, val handle: String, val name: String, val accent: String?, val hasAvatar: Boolean)

data class FriendLists(val friends: List<FriendLine> = emptyList(), val incoming: List<FriendLine> = emptyList(),
                       val outgoing: List<FriendLine> = emptyList())

/**
 * The friends state machine: a request, then a mutual friendship. Pure, so the app and its tests agree with
 * the server's statuses without a network.
 */
object FriendStates {
    private val handle = Regex("[a-z0-9_.]{3,24}")
    private val linkPath = Regex("(?:ketvoice://u/|https?://[A-Za-z0-9.-]+/u/)@?([A-Za-z0-9_.]{3,24})/?(?:[?#].*)?", RegexOption.IGNORE_CASE)

    /** The buttons offered in [state]. */
    fun actions(state: FriendState): List<FriendAction> = when (state) {
        FriendState.None -> listOf(FriendAction.Ask)
        FriendState.Outgoing -> listOf(FriendAction.Cancel)
        FriendState.Incoming -> listOf(FriendAction.Accept, FriendAction.Decline)
        FriendState.Friends -> listOf(FriendAction.Remove)
        FriendState.Self -> emptyList()
    }

    /** The state after the server answered [status] to [action] taken in [state]. */
    fun after(state: FriendState, action: FriendAction, status: String): FriendState = when (action) {
        FriendAction.Ask -> when (status) {
            "requested", "pending" -> FriendState.Outgoing
            "accepted", "friends" -> FriendState.Friends
            "self" -> FriendState.Self
            "unavailable" -> FriendState.None
            else -> state
        }
        FriendAction.Accept -> when (status) { "accepted" -> FriendState.Friends; "not_found" -> FriendState.None; else -> state }
        FriendAction.Decline -> if (status == "declined" || status == "not_found") FriendState.None else state
        FriendAction.Cancel, FriendAction.Remove -> if (status == "removed" || status == "not_found") FriendState.None else state
    }

    /** What to tell the listener (French, translated when drawn), or null when the state change says it all. */
    fun message(status: String): String? = when (status) {
        "requested" -> "Demande envoyée"
        "pending" -> "Demande déjà envoyée"
        "accepted" -> "Vous êtes maintenant amis"
        "friends" -> "Vous êtes déjà amis"
        "unavailable" -> "Profil introuvable"
        "self" -> "C'est votre propre profil"
        "needs_handle" -> "Choisissez d'abord un nom d'utilisateur dans votre profil"
        "limit" -> "Limite atteinte pour le moment"
        "declined" -> "Demande refusée"
        "removed" -> "Retiré"
        else -> null
    }

    /** A handle from what the listener typed or pasted: « @luna.k », « Luna.K », a ketvoice://u/ or …/u/ link. */
    fun handleFrom(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.length > 300) return null
        linkPath.matchEntire(text)?.let { return it.groupValues[1].lowercase() }
        return text.removePrefix("@").lowercase().takeIf { handle.matches(it) }
    }

    fun lists(answer: JSONObject?): FriendLists {
        if (answer == null) return FriendLists()
        return FriendLists(lines(answer.optJSONArray("friends")), lines(answer.optJSONArray("incoming")), lines(answer.optJSONArray("outgoing")))
    }

    private fun lines(rows: JSONArray?): List<FriendLine> = (0 until (rows?.length() ?: 0)).mapNotNull { i ->
        val o = rows!!.optJSONObject(i) ?: return@mapNotNull null
        val tag = o.optString("handle").takeIf { handle.matches(it) } ?: return@mapNotNull null
        val ref = o.optString("ref").takeIf { it.length in 8..64 } ?: return@mapNotNull null
        FriendLine(ref, tag, SocialText.name(o.optString("display_name")).ifBlank { tag },
            ProfileRules.accent(o.optString("accent").takeIf { !o.isNull("accent") }), o.optBoolean("has_avatar"))
    }
}

/** Cleaning of what other people wrote, before it is drawn. */
object SocialText {
    fun name(raw: String?, max: Int = 40): String =
        raw.orEmpty().filterNot { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }.trim().take(max)
}
