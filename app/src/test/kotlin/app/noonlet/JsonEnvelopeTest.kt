package app.noonlet

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36])
class JsonEnvelopeTest {
    @Test fun quotedSyntaxAndEscapesDoNotCountAsNesting() {
        val text = JSONObject().put("text", "# / ' [ { \" \\")
            .put("values", JSONArray().put(1).put(true).put(JSONObject.NULL)).toString()
        JsonEnvelope.validate(text, 4096, 2)
        JsonEnvelope.validate("\uFEFF" + text, 4096, 2)
    }

    @Test fun lenientSyntaxCannotHideNestedValues() {
        val nested = "[".repeat(700) + "0" + "]".repeat(700)
        for (text in listOf(
            "#\"\n{\"data\":" + nested + "}#\"",
            "{bad\":" + nested + ",end\":0}",
            "{unquoted:[]}", "{'data':[]}", "{\"data\":/* comment */[]}",
            "{\"data\":[]}{}", "{\"data\":[1,]}", "{\"data\":01}",
            "{\"data\":" + nested + "}",
        )) assertThrows(IllegalArgumentException::class.java) {
            JsonEnvelope.validate(text, 8192, 12)
        }
        assertThrows(IllegalArgumentException::class.java) { JsonEnvelope.validate("{}", 1, 12) }
    }
}
