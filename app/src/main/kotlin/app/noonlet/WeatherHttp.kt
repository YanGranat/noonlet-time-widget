package app.noonlet

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection

internal class WeatherRetry(val notBefore: Long) : IOException("Weather request deferred")

internal class WeatherHttp(
    private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = System::nanoTime,
) {
    private val deferred = mutableMapOf<String, Long>()

    fun read(address: String): WeatherResponse {
        val url = URL(address)
        require(url.protocol == "https")
        synchronized(deferred) {
            deferred.entries.removeAll { it.value <= now() }
            deferred[url.host]?.let { throw WeatherRetry(it) }
        }
        val started = elapsed()
        val connection = connect(url)
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 7_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Noonlet/Android")
            try {
                WeatherClient.requireSuccess(connection.responseCode)
            } catch (error: IOException) {
                val until = retryAt(connection.getHeaderField("Retry-After"), connection.date, now())
                if (until > now()) {
                    synchronized(deferred) { deferred[url.host] = maxOf(deferred[url.host] ?: 0L, until) }
                    throw WeatherRetry(until)
                }
                throw error
            }
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val duration = elapsed() - started
                    if (duration >= 15_000_000_000L || Thread.currentThread().isInterrupted)
                        throw IOException("Weather request timed out")
                    val remaining = 15_000 - duration / 1_000_000
                    connection.readTimeout = minOf(7_000L, remaining.coerceAtLeast(1L)).toInt()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 256 * 1024) throw IOException("Weather response is too large")
                    output.write(buffer, 0, count)
                }
                val date = connection.date.takeIf { it > 0 }
                val age = connection.getHeaderField("Age")?.toLongOrNull()?.takeIf { it >= 0 } ?: 0L
                val serverTime = date?.let {
                    runCatching { Math.addExact(it, Math.multiplyExact(age, 1000L)) }.getOrNull()
                }
                val body = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString()
                WeatherResponse(body, serverTime)
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val shared = WeatherHttp()

        internal fun retryAt(header: String?, serverDate: Long, time: Long): Long {
            val value = header?.trim()?.takeIf { it.isNotEmpty() && it.length <= 128 } ?: return 0
            val delay = if (value.all { it in '0'..'9' }) {
                val seconds = value.toLongOrNull() ?: Long.MAX_VALUE
                if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
            } else {
                val parsed = listOf("EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz",
                    "EEE MMM d HH:mm:ss yyyy").firstNotNullOfOrNull { pattern ->
                    val position = ParsePosition(0)
                    SimpleDateFormat(pattern, Locale.US).apply {
                        isLenient = false
                        timeZone = TimeZone.getTimeZone("GMT")
                    }.parse(value, position)?.takeIf { position.index == value.length }?.time
                } ?: return 0
                (parsed - (serverDate.takeIf { it > 0 } ?: time)).coerceAtLeast(0)
            }
            return if (delay > Long.MAX_VALUE - time) Long.MAX_VALUE else time + delay
        }
    }
}
