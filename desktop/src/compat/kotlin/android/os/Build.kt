package android.os

/** What the shared code reads of the device: on the desktop the system's name stands for the model. */
object Build {
    @JvmField val MANUFACTURER: String = ""
    @JvmField val MODEL: String = System.getProperty("os.name").orEmpty().ifBlank { "Windows" }
}
