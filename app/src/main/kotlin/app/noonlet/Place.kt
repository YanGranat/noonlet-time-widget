package app.noonlet

class Place(val id: String, val zoneId: String = id) {
    val country: Boolean = id.startsWith(COUNTRY_PREFIX)

    companion object {
        const val COUNTRY_PREFIX = "country:"
    }
}
