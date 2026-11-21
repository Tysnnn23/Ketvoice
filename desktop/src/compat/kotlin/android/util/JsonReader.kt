package android.util

import java.io.Closeable
import java.io.Reader

/** android.util.JsonReader (AppText streams translations.json with it), on Gson's reader of the same design. */
class JsonReader(input: Reader) : Closeable {
    private val reader = com.google.gson.stream.JsonReader(input)
    fun beginObject() = reader.beginObject()
    fun endObject() = reader.endObject()
    fun beginArray() = reader.beginArray()
    fun endArray() = reader.endArray()
    fun hasNext(): Boolean = reader.hasNext()
    fun nextName(): String = reader.nextName()
    fun nextString(): String = reader.nextString()
    fun skipValue() = reader.skipValue()
    fun peek(): JsonToken = JsonToken.valueOf(reader.peek().name)
    override fun close() = reader.close()
}

enum class JsonToken { BEGIN_ARRAY, END_ARRAY, BEGIN_OBJECT, END_OBJECT, NAME, STRING, NUMBER, BOOLEAN, NULL, END_DOCUMENT }
