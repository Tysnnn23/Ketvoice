package com.ketvoice.desktop.ui

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.vector.ImageVector

/*
 * Extension points of the Windows 2.0 shell. A new feature adds its page, its tab in the right panel or its section of
 * Settings here, from its own file, without touching the shell: see desktop/README.md « Extension points ».
 */

/**
 * Pages added by other features (Connexions, Sources, a profile, listening rooms…). Each is reached with
 * [Route.Extra] (`nav.root(Route.Extra("sources"))`, or `push` with an [Route.Extra.arg]) and, when [Page.sidebar]
 * is set, has an entry in the sidebar after the library, ordered by [Page.order].
 */
object PageRegistry {
    data class Page(
        val id: String,
        /** French; translated through AppText like every label. */
        val title: String,
        val icon: ImageVector,
        val sidebar: Boolean = true,
        val order: Int = 100,
        val content: @Composable (arg: String?) -> Unit,
    )

    private val pages = mutableStateListOf<Page>()

    /** Adds [page], replacing one with the same id. */
    fun register(page: Page) {
        pages.removeAll { it.id == page.id }
        pages += page
        pages.sortBy { it.order }
    }

    fun unregister(id: String) { pages.removeAll { it.id == id } }

    fun find(id: String): Page? = pages.firstOrNull { it.id == id }

    /** The pages with a sidebar entry, in their order. */
    val sidebar: List<Page> get() = pages.filter { it.sidebar }
}

/**
 * The tabs of the right glass panel: « Paroles », « File d'attente », « Artiste » and « Ketmix » are built in; a
 * feature adds its own with [register] (a live mix view, a room's chat…). The island's lyrics and queue buttons open
 * their tab; [ShellState.open] opens any of them.
 */
object PanelTabs {
    data class Tab(val id: String, val label: String, val icon: ImageVector, val order: Int, val content: @Composable () -> Unit)

    private val tabs = mutableStateListOf<Tab>()

    fun register(tab: Tab) {
        tabs.removeAll { it.id == tab.id }
        tabs += tab
        tabs.sortBy { it.order }
    }

    fun unregister(id: String) { tabs.removeAll { it.id == id } }

    /** Adds [tab] unless a feature already registered one with its id (how the built-in tabs give way to a richer one). */
    internal fun registerDefault(tab: Tab) { if (tabs.none { it.id == tab.id }) register(tab) }

    /** Every tab in its order, the built-in ones first registered. */
    val all: List<Tab> get() { BuiltInTabs.ensure(); return tabs }

    fun find(id: String?): Tab? = id?.let { all.firstOrNull { t -> t.id == it } }
}

/**
 * Sections other features add to Settings: drawn after the built-in section named [Section.after] (« Windows »,
 * « Lecteur »…, or « À propos » for the end), with their rows indexed for « Rechercher un réglage ».
 */
object SettingsSections {
    data class Section(
        /** The header's item key on the page (`h:<key>`), unique. */
        val key: String,
        /** The header, in French. */
        val title: String,
        /** The built-in section it follows (its French header). */
        val after: String = "Windows",
        /** The names of its rows, for the settings search. */
        val rows: List<String> = emptyList(),
        val content: @Composable () -> Unit,
    )

    private val sections = mutableStateListOf<Section>()

    fun register(section: Section) {
        unregister(section.key)
        sections += section
        SettingsFind.more += section.rows.map { SettingsFind.Entry(it, section.title, "h:${section.key}") }
    }

    fun unregister(key: String) {
        val old = sections.firstOrNull { it.key == key } ?: return
        sections.remove(old)
        SettingsFind.more.removeAll { it.sectionKey == "h:$key" }
    }

    fun after(anchor: String): List<Section> = sections.filter { it.after == anchor }
}

/** The sections registered after [anchor], each with its header (item `h:<key>`) and its content. */
internal fun LazyListScope.extraSettings(anchor: String) {
    SettingsSections.after(anchor).forEach { s ->
        item("h:${s.key}") { SectionHeader(s.title) }
        item("x:${s.key}") { s.content() }
    }
}

/**
 * Small marks beside the title on the island (« Mix en cours » is built in): a feature shows its state there, e.g. a
 * listening room's « Ensemble ». Each draws nothing when it has nothing to say.
 */
object IslandMarks {
    data class Mark(val id: String, val content: @Composable () -> Unit)

    private val marks = mutableStateListOf<Mark>()

    fun register(mark: Mark) { marks.removeAll { it.id == mark.id }; marks += mark }
    fun unregister(id: String) { marks.removeAll { it.id == id } }
    val all: List<Mark> get() = marks
}

/**
 * Blocks a feature shows in the unfolded full player, under the transport (Windows 2.0): the live mix view while
 * Ketmix crosses two titles. Each draws nothing when it has nothing to say. [Slot.content] gets the full player's
 * glass backdrop (its cover colours) and whether the player is compact (the lyrics are open beside it).
 */
object FullPlayerSlots {
    data class Slot(val id: String, val order: Int = 100,
                    val content: @Composable (backdrop: com.ketvoice.desktop.ui.glass.GlassBackdrop?, compact: Boolean) -> Unit)

    private val slots = mutableStateListOf<Slot>()

    fun register(slot: Slot) { slots.removeAll { it.id == slot.id }; slots += slot; slots.sortBy { it.order } }
    fun unregister(id: String) { slots.removeAll { it.id == id } }
    val all: List<Slot> get() = slots
}

/**
 * Invisible pieces kept composed for the whole life of the window, with the shell's locals (navigator, panel, menu):
 * a feature that acts on the window from outside (a `ketvoice://` link opened from the browser) reacts there.
 */
object ShellHooks {
    data class Hook(val id: String, val content: @Composable () -> Unit)

    private val hooks = mutableStateListOf<Hook>()

    fun register(hook: Hook) { hooks.removeAll { it.id == hook.id }; hooks += hook }
    fun unregister(id: String) { hooks.removeAll { it.id == id } }
    val all: List<Hook> get() = hooks
}
