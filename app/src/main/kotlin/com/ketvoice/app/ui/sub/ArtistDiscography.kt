package com.ketvoice.app.ui.sub

import com.ketvoice.app.data.Album

/**
 * How an artist's releases are laid out on the artist page (6.1): what the catalogue gives is a
 * list of releases whose type reads « Album · 2023 », « Single · 2022 » or « EP ». From it come the
 * latest release, and one shelf per kind; a kind with nothing in it has no shelf.
 */
internal object ArtistDiscography {
    enum class Kind { ALBUM, SINGLE, EP }

    /** A shelf of the discography: its French title (translated at display), its test tag and its releases. */
    data class Shelf(val title: String, val tag: String, val albums: List<Album>, val singles: Boolean)

    /** How many popular titles show before « Tout afficher ». */
    const val POPULAR_PREVIEW = 5

    private val yearPattern = Regex("\\b(?:19|20)\\d{2}\\b")

    fun kind(album: Album): Kind = when (album.type.substringBefore("·").trim().lowercase()) {
        "single" -> Kind.SINGLE
        "ep" -> Kind.EP
        else -> Kind.ALBUM
    }

    fun year(album: Album): Int? = yearPattern.find(album.type)?.value?.toIntOrNull()

    /**
     * The most recent release whose year is known; among releases of the same year the one listed
     * first (the catalogue lists the newest first). Null when no year is known: the card is hidden
     * rather than guessing.
     */
    fun latest(albums: List<Album>): Album? = albums.withIndex()
        .mapNotNull { (index, album) -> year(album)?.let { Triple(it, -index, album) } }
        .maxWithOrNull(compareBy<Triple<Int, Int, Album>> { it.first }.thenBy { it.second })?.third

    fun shelves(albums: List<Album>): List<Shelf> = listOf(
        Shelf("Albums", "artist-shelf-albums", albums.filter { kind(it) == Kind.ALBUM }, singles = false),
        Shelf("Singles et EP", "artist-shelf-singles", albums.filter { kind(it) != Kind.ALBUM }, singles = true)
    ).filter { it.albums.isNotEmpty() }

    /**
     * The line under a release in a shelf: the year alone in the Albums shelf (its kind is the
     * shelf's title), the kind and the year among singles and EPs, where both kinds mix.
     */
    fun caption(album: Album, singles: Boolean): String {
        val year = year(album)
        if (!singles && year != null) return year.toString()
        val label = when (kind(album)) { Kind.SINGLE -> "Single"; Kind.EP -> "EP"; Kind.ALBUM -> "Album" }
        return if (year != null) "$label · $year" else label
    }

    fun popular(tracks: List<com.ketvoice.app.data.Track>, expanded: Boolean) =
        if (expanded) tracks else tracks.take(POPULAR_PREVIEW)
}

/** The geometry of the artist page, in dp, from the width of its window (never the device). */
internal object ArtistLayout {
    /** From this width the page uses the room: a banner, titles in columns beside the latest release. */
    const val WIDE_FROM = 720f

    fun wide(widthDp: Float, quest: Boolean = com.ketvoice.app.BuildConfig.IS_QUEST): Boolean = !quest && widthDp >= WIDE_FROM

    /** The side margin of the page. */
    fun margin(wide: Boolean): Float = if (wide) 32f else 20f

    /**
     * Height of the hero. A phone opens on a tall portrait (a little taller than wide), a wide window
     * on a banner; neither ever takes more than about two thirds of the window, so the name and the
     * buttons are always on the first screen.
     */
    fun heroHeight(widthDp: Float, windowHeightDp: Float, wide: Boolean): Float {
        val window = if (windowHeightDp.isFinite() && windowHeightDp > 0f) windowHeightDp else 800f
        // 7.4: an upright phone opens on the photo over about 58 % of its height, up under the status bar;
        // a wide or landscape window (tablet, Quest, a phone on its side) keeps a capped banner.
        return if (wide || widthDp > window) minOf(widthDp * .42f, window * .56f).coerceIn(300f, 560f)
        else minOf(window * PHONE_SHARE, widthDp * 1.6f).coerceIn(340f, 640f)
    }

    /** Share of an upright phone's window the hero takes. */
    const val PHONE_SHARE = .58f

    /** How many columns the popular titles take in an area [areaDp] wide: two once a row keeps 380 dp. */
    fun popularColumns(areaDp: Float): Int = if (areaDp >= 760f) 2 else 1
}
