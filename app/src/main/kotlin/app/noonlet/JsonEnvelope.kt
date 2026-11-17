package app.noonlet

import android.util.JsonReader
import android.util.JsonToken
import java.io.IOException
import java.io.StringReader

internal object JsonEnvelope {
    fun validate(text: String, maxCharacters: Int, maxDepth: Int) {
        require(text.length <= maxCharacters)
        try {
            JsonReader(StringReader(text.removePrefix("\uFEFF"))).use { reader ->
                reader.isLenient = false
                require(reader.peek() == JsonToken.BEGIN_OBJECT)
                var depth = 0
                while (true) {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> { require(++depth <= maxDepth); reader.beginObject() }
                        JsonToken.BEGIN_ARRAY -> { require(++depth <= maxDepth); reader.beginArray() }
                        JsonToken.END_OBJECT -> { reader.endObject(); depth-- }
                        JsonToken.END_ARRAY -> { reader.endArray(); depth-- }
                        JsonToken.NAME -> reader.nextName()
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        JsonToken.END_DOCUMENT -> break
                    }
                }
                require(depth == 0)
            }
        } catch (error: IOException) {
            throw IllegalArgumentException("Invalid JSON", error)
        }
    }
}
