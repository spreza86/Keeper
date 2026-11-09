package com.preza.keeper.data

import android.content.Context
import java.time.Instant
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline place names. Two lists ship inside the app (from GeoNames):
 *  - cities.csv: about 34,000 cities with population, used for the city name
 *  - areas.csv: about 145,000 smaller places, used for the neighbourhood or area
 * No network call is ever made.
 */
class Geo(private val ctx: Context) {

    private class P(val lat: Double, val lon: Double, val name: String, val cc: String, val pop: Int)

    private val cities = HashMap<Int, MutableList<P>>()
    private val areas = HashMap<Int, MutableList<P>>()
    private val countries = HashMap<String, String>()
    @Volatile private var loaded = false

    @Synchronized
    private fun ensure() {
        if (loaded) return
        load("cities.csv", cities, withPop = true)
        load("areas.csv", areas, withPop = false)
        ctx.assets.open("countries.csv").bufferedReader().useLines { lines ->
            lines.forEach { l ->
                val i = l.indexOf(',')
                if (i > 0) countries[l.substring(0, i)] = l.substring(i + 1)
            }
        }
        loaded = true
    }

    private fun load(name: String, into: HashMap<Int, MutableList<P>>, withPop: Boolean) {
        ctx.assets.open(name).bufferedReader().useLines { lines ->
            lines.forEach { l ->
                val parts = l.split(',')
                if (parts.size < 4) return@forEach
                val lat = parts[0].toDoubleOrNull() ?: return@forEach
                val lon = parts[1].toDoubleOrNull() ?: return@forEach
                val pop = if (withPop) parts.getOrNull(4)?.toIntOrNull() ?: 0 else 0
                val p = P(lat, lon, parts[2], parts[3], pop)
                into.getOrPut(cell(lat, lon)) { mutableListOf() }.add(p)
            }
        }
    }

    private fun cell(lat: Double, lon: Double) = ((floor(lat) + 90) * 360 + (floor(lon) + 180)).toInt()

    private fun near(map: HashMap<Int, MutableList<P>>, lat: Double, lon: Double): Sequence<Pair<P, Double>> = sequence {
        val la = floor(lat).toInt()
        val lo = floor(lon).toInt()
        for (dy in -1..1) for (dx in -1..1) {
            val key = ((la + dy + 90) * 360 + (lo + dx + 180))
            map[key]?.forEach { yield(it to km(lat, lon, it.lat, it.lon)) }
        }
    }

    class Result(val city: String, val area: String?, val country: String, val cc: String)

    fun lookup(lat: Double, lon: Double): Result? {
        ensure()
        // City: a big city nearby wins over a district, so Mong Kok becomes Hong Kong
        // (with Mong Kok as the area) while Hoi An stays Hoi An rather than Da Nang.
        val city = near(cities, lat, lon)
            .filter { it.second < 30.0 }
            .maxByOrNull { val k = 1 + it.second / 3; it.first.pop / (k * k) }
            ?.first ?: return null
        val area = (near(cities, lat, lon) + near(areas, lat, lon))
            .filter { it.second < 4.0 }
            .minByOrNull { it.second }
            ?.first?.name?.takeIf { norm(it) != norm(city.name) }
        return Result(city.name, area, countries[city.cc] ?: city.cc, city.cc)
    }

    fun countryName(cc: String): String {
        ensure()
        return countries[cc] ?: cc
    }

    companion object {
        /** "Montréal" and "Montreal" are the same place. */
        fun norm(s: String): String =
            java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

        fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(a))
        }
    }
}

/** Sun altitude in degrees, for telling golden hour, blue hour and night apart. */
object Sun {
    fun altitude(t: Instant, lat: Double, lon: Double): Double {
        val n = t.toEpochMilli() / 86_400_000.0 + 2440587.5 - 2451545.0
        val l = ((280.460 + 0.9856474 * n) % 360 + 360) % 360
        val g = Math.toRadians(((357.528 + 0.9856003 * n) % 360 + 360) % 360)
        val lambda = Math.toRadians(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val dec = asin(sin(eps) * sin(lambda))
        val gmst = ((18.697374558 + 24.06570982441908 * n) % 24 + 24) % 24
        val ha = Math.toRadians(gmst * 15 + lon) - ra
        val latR = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(latR) * sin(dec) + cos(latR) * cos(dec) * cos(ha)))
    }
}
