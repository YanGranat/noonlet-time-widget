package app.noonlet

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.cert.Certificate
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class WeatherHttpTest {
    @Test fun successfulResponsesKeepLimitsHeadersAndServerAge() {
        for (body in listOf(byteArrayOf(), "{}".toByteArray(), ByteArray(256 * 1024) { 97 })) {
            val connection = Connection(body = body, headers = mapOf(
                "Date" to "Sun, 06 Nov 1994 08:49:37 GMT", "Age" to "25"))
            val response = WeatherHttp(connect = { connection }).read(ADDRESS)
            assertArrayEquals(body, response.body.toByteArray())
            assertEquals(Instant.parse("1994-11-06T08:50:02Z").toEpochMilli(), response.serverTime)
            assertEquals("application/json", connection.getRequestProperty("Accept"))
            assertEquals(5_000, connection.connectTimeout)
            assertTrue(connection.readTimeout in 1..7_000)
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.closed)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun oversizedMalformedAndFailedStreamsAlwaysClose() {
        val cases = listOf(Connection(body = ByteArray(256 * 1024 + 1)),
            Connection(body = byteArrayOf(0xc3.toByte())), Connection(failRead = true))
        for (connection in cases) {
            assertThrows(IOException::class.java) { WeatherHttp(connect = { connection }).read(ADDRESS) }
            assertTrue(connection.closed)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun redirectsAndPermanentErrorsDoNotReadAnErrorBodyOrRetry() {
        for (status in listOf(302, 400, 401, 403, 404)) {
            val connection = Connection(status = status, headers = mapOf("Retry-After" to "120"))
            assertThrows(WeatherFailure::class.java) { WeatherHttp(connect = { connection }).read(ADDRESS) }
            assertFalse(connection.opened)
            assertTrue(connection.disconnected)
            assertFalse(connection.instanceFollowRedirects)
        }
        assertThrows(IllegalArgumentException::class.java) { WeatherHttp().read("http://weather.example/data") }
    }

    @Test fun serverDelayBlocksItsHostUntilExpiryWithoutBlockingAnotherService() {
        var time = 1_850_000_000_000L
        var calls = 0
        var status = 429
        val transport = WeatherHttp(connect = { url ->
            calls++
            Connection(status = if (url.host == "weather.example") status else 200,
                headers = mapOf("Retry-After" to "120"))
        }, now = { time })
        assertEquals(time + 120_000, assertThrows(WeatherRetry::class.java) { transport.read(ADDRESS) }.notBefore)
        status = 200
        time += 119_999
        assertThrows(WeatherRetry::class.java) { transport.read(ADDRESS) }
        assertEquals(1, calls)
        assertEquals("{}", transport.read("https://search.example/data").body)
        assertEquals(2, calls)
        time++
        assertEquals("{}", transport.read(ADDRESS).body)
        assertEquals(3, calls)
    }

    @Test fun retryDatesUseTheServerClockAndNumericDelaysDoNotOverflow() {
        val date = Instant.parse("1994-11-06T08:49:37Z").toEpochMilli()
        for (header in listOf("Sun, 06 Nov 1994 08:51:37 GMT", "Sunday, 06-Nov-94 08:51:37 GMT",
            "Sun Nov 6 08:51:37 1994")) {
            assertEquals(date + 86_520_000, WeatherHttp.retryAt(header, date, date + 86_400_000))
        }
        assertEquals(Long.MAX_VALUE, WeatherHttp.retryAt("999999999999999999999999", date, date))
        for (header in listOf("", "-1", "+1", "1.5", "later"))
            assertEquals(0L, WeatherHttp.retryAt(header, date, date))
        assertEquals(date, WeatherHttp.retryAt("Sun, 06 Nov 1994 08:48:37 GMT", date, date))
        for (status in listOf(408, 429, 500, 503)) {
            val connection = Connection(status = status)
            assertThrows(IOException::class.java) { WeatherHttp(connect = { connection }).read(ADDRESS) }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun aTotalDeadlineOrInterruptionStopsReadingAndClosesTheConnection() {
        val connection = Connection()
        var ticks = 0
        assertThrows(IOException::class.java) {
            WeatherHttp(connect = { connection }, elapsed = { if (ticks++ == 0) 0 else 15_000_000_000L }).read(ADDRESS)
        }
        assertTrue(connection.closed)
        assertTrue(connection.disconnected)
        val interrupted = Connection()
        try {
            Thread.currentThread().interrupt()
            assertThrows(IOException::class.java) { WeatherHttp(connect = { interrupted }).read(ADDRESS) }
            assertTrue(interrupted.closed)
            assertTrue(interrupted.disconnected)
        } finally { Thread.interrupted() }
    }

    private class Connection(
        private val body: ByteArray = "{}".toByteArray(),
        private val status: Int = 200,
        private val headers: Map<String, String> = emptyMap(),
        private val failRead: Boolean = false,
    ) : HttpsURLConnection(URL(ADDRESS)) {
        var closed = false
        var opened = false
        var disconnected = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        override fun getInputStream(): InputStream {
            opened = true
            return object : ByteArrayInputStream(body) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (failRead) throw IOException("Stream unavailable")
                    return super.read(bytes, offset, length)
                }
                override fun close() { closed = true; super.close() }
            }
        }
        override fun disconnect() { disconnected = true }
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun getCipherSuite() = "TLS_AES_128_GCM_SHA256"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }

    companion object { private const val ADDRESS = "https://weather.example/data" }
}
