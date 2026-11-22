package android.os

/** What the shared code reads of the device: on the desktop the system's name stands for the model. */
object Build {
    @JvmField val MANUFACTURER: String = ""
    @JvmField val MODEL: String = System.getProperty("os.name").orEmpty().ifBlank { "Windows" }

    /** The desktop draws what the phone draws from Android 15 on (blur effects through Skia). */
    object VERSION { @JvmField val SDK_INT: Int = 35 }
}
