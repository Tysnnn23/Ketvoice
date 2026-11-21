package android.icu.text

/** android.icu is ICU4J repackaged; on the desktop the real ICU4J does the work. */
class Transliterator private constructor(private val icu: com.ibm.icu.text.Transliterator) {
    fun transliterate(text: String): String = icu.transliterate(text)

    companion object {
        @JvmStatic
        fun getInstance(id: String): Transliterator = Transliterator(com.ibm.icu.text.Transliterator.getInstance(id))
    }
}
