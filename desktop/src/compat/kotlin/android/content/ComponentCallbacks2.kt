package android.content

/** Android's memory-trim levels (the values the shared code compares against). */
interface ComponentCallbacks2 {
    companion object {
        const val TRIM_MEMORY_RUNNING_MODERATE = 5
        const val TRIM_MEMORY_RUNNING_LOW = 10
        const val TRIM_MEMORY_RUNNING_CRITICAL = 15
        const val TRIM_MEMORY_UI_HIDDEN = 20
        const val TRIM_MEMORY_BACKGROUND = 40
        const val TRIM_MEMORY_MODERATE = 60
        const val TRIM_MEMORY_COMPLETE = 80
    }
}
