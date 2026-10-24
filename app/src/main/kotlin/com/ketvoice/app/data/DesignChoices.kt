package com.ketvoice.app.data

import androidx.compose.runtime.mutableStateOf

data class DesignChoice(val code: String, val title: String)
object DesignChoices {
    val all = listOf(
        DesignChoice("D1", "Signature Ketvoice"), DesignChoice("D2", "Accueil éditorial"),
        DesignChoice("D3", "Cartes de collections"), DesignChoice("D4", "Typographie harmonisée"),
        DesignChoice("D5", "Menus unifiés"), DesignChoice("D7", "Éditeur visuel des imports"),
        DesignChoice("D8", "Finitions Quest"))
    private val states = all.associate { it.code to mutableStateOf(true) }
    fun enabled(code: String): Boolean = states[code]?.value ?: false
    fun set(code: String, enabled: Boolean) { states[code]?.value = enabled }
}
