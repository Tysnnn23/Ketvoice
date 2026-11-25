package com.ketvoice.app.social

/**
 * Who may see a part of a listener's profile (Windows 2.0 social). The server applies the same rule in
 * `ketvoice_profile_can_see` (supabase/migrations/20261125120400_social_profile_view.sql); both are tested on the
 * same table of cases, so the app never shows a switch that promises more than the server lets through.
 * Everything starts private: a new module is [Reach.Me] until its owner widens it.
 */
enum class Reach(val wire: String, val label: String) {
    Me("me", "Moi"), Friends("friends", "Amis"), Public("public", "Public");

    companion object {
        /** Anything unknown is read as the most private reach. */
        fun of(wire: String?): Reach = entries.firstOrNull { it.wire == wire } ?: Me
    }
}

/** How the person looking relates to the profile's owner. */
enum class Relation(val wire: String) {
    Me("me"), Friend("friend"), Stranger("stranger");

    companion object {
        fun of(wire: String?): Relation = entries.firstOrNull { it.wire == wire } ?: Stranger
    }
}

/** The profile's modules, in the order the page shows them. */
enum class ProfileModule(val wire: String, val label: String) {
    Now("now", "En cours d'écoute"), Top("top", "Artistes du mois"), Pinned("pinned", "Playlists épinglées");

    companion object {
        fun of(wire: String?): ProfileModule? = entries.firstOrNull { it.wire == wire }
    }
}

object ProfileRules {
    const val BIO_MAX = 160
    const val TOP_MAX = 5
    const val PINNED_MAX = 6

    /** A module set to [reach] is shown to [relation]; strangers see public modules only while the profile is public. */
    fun canSee(relation: Relation, reach: Reach, profilePublic: Boolean): Boolean = when (relation) {
        Relation.Me -> true
        Relation.Friend -> reach == Reach.Friends || reach == Reach.Public
        Relation.Stranger -> reach == Reach.Public && profilePublic
    }

    /** Name, photo, banner, accent and bio: the owner and friends always, anyone else only on a public profile. */
    fun identityVisible(relation: Relation, profilePublic: Boolean): Boolean = relation != Relation.Stranger || profilePublic

    /** What [reach] really means for someone who is not a friend: « Public » on a private profile reaches friends only. */
    fun effective(reach: Reach, profilePublic: Boolean): Reach = if (reach == Reach.Public && !profilePublic) Reach.Friends else reach

    /** One line, no control characters, at most [BIO_MAX] characters (the server's check). */
    fun cleanBio(raw: String): String =
        raw.map { if (it.isISOControl()) ' ' else it }.filterNot { it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }
            .joinToString("").replace(Regex("\\s+"), " ").trim().take(BIO_MAX)

    /** `#RRGGBB` in capitals (the server's check), or null. Accepts « ff2d55 », « #Ff2D55 ». */
    fun accent(raw: String?): String? {
        val hex = raw?.trim()?.removePrefix("#")?.uppercase() ?: return null
        return if (hex.length == 6 && hex.all { it in '0'..'9' || it in 'A'..'F' }) "#$hex" else null
    }

    /** The accent as an opaque ARGB colour, or null. */
    fun accentArgb(raw: String?): Long? = accent(raw)?.let { 0xFF000000L or it.drop(1).toLong(16) }
}
