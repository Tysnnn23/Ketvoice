package com.ketvoice.app.ui.together

import androidx.compose.ui.graphics.Color
import com.ketvoice.app.data.AppText
import com.ketvoice.app.together.TogetherRole
import com.ketvoice.app.together.TogetherUi

/** Ketogether's colours: the live dot, and the dot while the room reconnects. */
internal object KetogetherLook {
    val live = Color(0xFF34C759)
    val waiting = Color(0xFFFF9F0A)
    private val avatars = listOf(Color(0xFF0088FF), Color(0xFFFF2D55), Color(0xFF34C759), Color(0xFFFF8D28),
        Color(0xFF6155F5), Color(0xFF00C3D0), Color(0xFFCB30E0), Color(0xFFFFCC00))
    fun avatar(id: String): Color = avatars[Math.floorMod(id.hashCode(), avatars.size)]

    /** A line of the recent activity (8.4), as a French source text with the name and the title put in. */
    fun activity(a: com.ketvoice.app.together.Activity): String = AppText.t(when (a.kind) {
        "pause" -> "{0} a mis en pause"
        "play" -> "{0} a relancé la lecture"
        "next" -> "{0} est passé au titre suivant"
        "previous" -> "{0} est revenu au titre précédent"
        "seek" -> "{0} a déplacé la lecture"
        "add" -> "{0} a ajouté {1}"
        "join" -> "{0} a rejoint la session"
        "away" -> "{0} est absent · place gardée"
        "back" -> "{0} est de retour"
        "leave" -> "{0} a quitté la session"
        else -> "{0} a été retiré de la session"
    }).replace("{0}", a.name).replace("{1}", a.title.orEmpty())

    /**
     * The live « Synchronisé · N ms » (8.4): on a guest's phone how far it is from the host; on the host's, the
     * furthest guest at their last report. Null while nothing has been measured yet.
     */
    fun syncMs(ui: TogetherUi): Long? = if (ui.role == TogetherRole.Guest) ui.driftMs?.let { kotlin.math.abs(it) }
        else ui.members.filter { !it.me && !it.away }.mapNotNull { it.syncMs }.maxOrNull()

    /** What the end of a session says, as a French source text. */
    fun ending(reason: String?): String = when (reason) {
        "hostEnded" -> "L'hôte a terminé la session"
        "hostLost" -> "L'hôte s'est déconnecté · la session a pris fin"
        "notFound" -> "Aucune session avec ce code"
        "expired" -> "La session a pris fin : personne ne l'a rejointe"
        "connection" -> "Connexion perdue · la session a pris fin"
        "refused" -> "Le serveur n'accepte pas la session pour le moment"
        "left" -> "Vous avez quitté la session"
        "removed" -> "L'hôte vous a retiré de la session"
        "full" -> "La session est complète"
        else -> "Session terminée"
    }
}
