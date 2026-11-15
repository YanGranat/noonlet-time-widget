package app.noonlet

import android.content.Context
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal object ImportedWidgetConfigurations {
    data class Item(val token: String, val world: Boolean, val configuration: SettingsArchive.Configuration)
    private fun stored(context: Context) = context.getSharedPreferences("noonlet_imported_widgets", Context.MODE_PRIVATE)

    fun all(context: Context): List<Item> = try {
        val text = stored(context).getString("items", "[]") ?: "[]"
        require(text.length <= SettingsArchive.MAX_BYTES * 2)
        val items = JSONArray(text)
        require(items.length() <= 128)
        (0 until items.length()).map {
            val item = items.getJSONObject(it)
            val world = item.getBoolean("world")
            UUID.fromString(item.getString("token"))
            Item(item.getString("token"), world, SettingsArchive.decodeConfiguration(item.getJSONObject("configuration"), world))
        }
    } catch (_: Exception) { emptyList() }

    fun get(context: Context, token: String?): Item? = token?.let { key -> all(context).firstOrNull { it.token == key } }

    @Synchronized
    fun replace(context: Context, values: List<Pair<Boolean, SettingsArchive.Configuration>>) =
        write(context, values.map { Item(UUID.randomUUID().toString(), it.first, it.second) })

    @Synchronized
    fun remove(context: Context, token: String?) {
        if (token != null) write(context, all(context).filter { it.token != token })
    }

    @Synchronized
    fun updateSettings(context: Context, token: String, settings: WidgetSettings): Boolean {
        val values = all(context).toMutableList()
        val index = values.indexOfFirst { it.token == token }
        if (index < 0) return false
        val item = values[index]
        val normalized = WidgetSettings.defaults(item.world).apply {
            SettingsCodec.readFrom(this, SettingsCodec.snapshot(settings))
        }
        values[index] = item.copy(configuration = item.configuration.copy(settings = normalized))
        stored(context).edit().putString("items", encode(values)).apply()
        return true
    }

    private fun encode(values: List<Item>): String =
        JSONArray(values.map { item -> JSONObject().put("token", item.token).put("world", item.world)
            .put("configuration", SettingsArchive.configurationJson(item.configuration)) }).toString()

    private fun write(context: Context, values: List<Item>) {
        check(stored(context).edit().putString("items", encode(values)).commit())
    }
}
