package com.ketvoice.app.sync

import android.content.Context
import android.os.Build
import java.util.UUID

/**
 * This installation as the account's other devices name it: a random id made once (not a hardware
 * identifier) and the device's model (« Pixel 9 », « Meta Quest 3 »), shown in « En cours sur … ».
 */
internal object DeviceIdentity {
    private const val PREFS = "ketvoice-device"

    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("id", null)?.takeIf { it.matches(Regex("[A-Za-z0-9-]{8,64}")) }?.let { return it }
        return UUID.randomUUID().toString().also { prefs.edit().putString("id", it).apply() }
    }

    fun name(): String = label(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty())

    internal fun label(manufacturer: String, model: String): String {
        val brand = manufacturer.trim().replaceFirstChar { it.uppercase() }
        val name = model.trim()
        val text = when {
            name.isEmpty() -> brand
            brand.isEmpty() || name.startsWith(brand, ignoreCase = true) -> name
            brand.equals("Oculus", true) || brand.equals("Meta", true) -> if ("quest" in name.lowercase()) "Meta $name" else name
            else -> "$brand $name"
        }
        return text.filterNot(Char::isISOControl).take(40).ifBlank { "Appareil" }
    }
}
