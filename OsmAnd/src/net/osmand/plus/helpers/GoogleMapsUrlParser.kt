package net.osmand.plus.helpers

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.openlocationcode.OpenLocationCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern
import kotlin.coroutines.resume

data class GpsPoint(val latitude: Double, val longitude: Double)

data class ParsedNavigationRoute(
    val origin: GpsPoint? = null,
    val destination: GpsPoint? = null,
    val originRaw: String? = null,
    val destinationRaw: String? = null,
    val expandedUrl: String
)

class GoogleMapsUrlParser(private val context: Context) {

    companion object {
        private const val TAG = "GoogleMapsUrlParser"

        private val LAT_LNG_REGEX = Pattern.compile(
            "^\\s*([+-]?([1-8]?\\d(\\.\\d+)?|90(\\.0+)?))\\s*,\\s*([+-]?(180(\\.0+)?|((1[0-7]\\d)|([1-9]?\\d))(\\.\\d+)?))\\s*$"
        )

        private val PROTOBUF_PLACE_PIN_REGEX = Pattern.compile("!3d([+-]?\\d+\\.\\d+)!4d([+-]?\\d+\\.\\d+)")
        private val PROTOBUF_DIR_PIN_REGEX = Pattern.compile("!1d([+-]?\\d+\\.\\d+)!2d([+-]?\\d+\\.\\d+)")
        private val VIEWPORT_REGEX = Pattern.compile("@([+-]?\\d+\\.\\d+),([+-]?\\d+\\.\\d+)")
        private val EXTRACT_URL_REGEX = Pattern.compile("https?://\\S+")
        
        // Matches Plus Codes like 8FW4XM3F+2J or XM3F+2J
        private val PLUS_CODE_REGEX = Pattern.compile("\\b([2-9CFGHJMPQRVWX]{4,8}\\+[2-9CFGHJMPQRVWX]{2,3})\\b", Pattern.CASE_INSENSITIVE)
    }

    suspend fun parse(input: String): ParsedNavigationRoute = withContext(Dispatchers.IO) {
        val targetUrl = extractUrlFromText(input) ?: input.trim()
        Log.d(TAG, "[Parse] Input: $input")

        val expandedUrl = expandUrl(targetUrl)
        Log.d(TAG, "[Parse] Expanded: $expandedUrl")

        val uri = Uri.parse(expandedUrl)
        val encodedPath = uri.encodedPath ?: ""

        var rawOrigin: String? = null
        var rawDestination: String? = null
        var pinCoords: GpsPoint? = null
        var viewportCoords: GpsPoint? = null
        var fallbackMetadataCoords: GpsPoint? = null

        // 1. Metadata extraction (Absolute last resort fallback)
        val metadataCoords = uri.getQueryParameter("direct_coords")
        if (!metadataCoords.isNullOrBlank()) {
            val parts = metadataCoords.split(",")
            if (parts.size == 2) {
                val lat = parts[0].toDoubleOrNull()
                val lng = parts[1].toDoubleOrNull()
                if (lat != null && lng != null) {
                    fallbackMetadataCoords = GpsPoint(lat, lng)
                }
            }
        }

        // 2. Pattern Matching
        when {
            // Pattern 1: Web API Directions
            uri.getQueryParameter("origin") != null || uri.getQueryParameter("destination") != null ||
            uri.getQueryParameter("saddr") != null || uri.getQueryParameter("daddr") != null -> {
                Log.d(TAG, "[Parse] Pattern: Web API Directions")
                rawOrigin = uri.getQueryParameter("origin") ?: uri.getQueryParameter("saddr")
                rawDestination = uri.getQueryParameter("destination") ?: uri.getQueryParameter("daddr")
                pinCoords = extractProtobufCoordinates(expandedUrl)
            }

            // Pattern 2A: Web API Search (Parameters)
            uri.getQueryParameter("query") != null || uri.getQueryParameter("q") != null -> {
                Log.d(TAG, "[Parse] Pattern: Web API Search (Params)")
                rawDestination = uri.getQueryParameter("query") ?: uri.getQueryParameter("q")
                pinCoords = extractProtobufCoordinates(expandedUrl)
            }

            // Pattern 2B: Web API Search (Path-based) e.g. /maps/search/40.35,23.23
            encodedPath.contains("/search/") -> {
                Log.d(TAG, "[Parse] Pattern: Web API Search (Path)")
                val segment = encodedPath.substringAfter("/search/").substringBefore("/")
                rawDestination = decodeUrlSegment(segment)
                pinCoords = extractProtobufCoordinates(expandedUrl)
            }

            // Pattern 3: Path Directions
            encodedPath.contains("/dir/") -> {
                Log.d(TAG, "[Parse] Pattern: Path Directions")
                val segments = encodedPath.substringAfter("/dir/").split("/").filter { it.isNotBlank() }
                val stops = segments.takeWhile { !it.startsWith("@") && !it.contains("data=") }
                if (stops.isNotEmpty()) {
                    rawOrigin = decodeUrlSegment(stops[0])
                    if (stops.size > 1) rawDestination = decodeUrlSegment(stops.last())
                }
                pinCoords = extractProtobufCoordinates(expandedUrl)
            }

            // Pattern 4: Place URL
            encodedPath.contains("/place/") -> {
                Log.d(TAG, "[Parse] Pattern: Place URL")
                val placeSegment = encodedPath.substringAfter("/place/").substringBefore("/")
                rawDestination = decodeUrlSegment(placeSegment)
                pinCoords = extractProtobufCoordinates(expandedUrl)
            }
        }

        viewportCoords = extractViewportCoordinates(expandedUrl)

        // 3. Resolve Coords
        val originCoords = rawOrigin?.let { resolveLocationString(it) }

        // PRIORITY: 1. Pin, 2. Resolved String (Plus Code/Name), 3. Viewport, 4. Metadata
        val destCoords = pinCoords 
            ?: (rawDestination?.let { resolveLocationString(it) })
            ?: viewportCoords
            ?: fallbackMetadataCoords

        Log.d(TAG, "[Final] Origin: $originCoords, Destination: $destCoords")

        ParsedNavigationRoute(
            origin = originCoords,
            destination = destCoords,
            originRaw = rawOrigin,
            destinationRaw = rawDestination,
            expandedUrl = expandedUrl
        )
    }

    private fun extractUrlFromText(text: String): String? {
        val matcher = EXTRACT_URL_REGEX.matcher(text)
        return if (matcher.find()) matcher.group(0) else null
    }

    private fun expandUrl(shortUrl: String, maxRedirects: Int = 10): String {
        var currentUrl = shortUrl
        var redirects = 0
        // Use a more standard generic User-Agent for dynamic link compatibility
        val genericUA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        while (redirects < maxRedirects) {
            Log.d(TAG, "[Expand] Step $redirects: $currentUrl")
            
            if (currentUrl.contains("consent.google.com")) {
                val uri = Uri.parse(currentUrl)
                val continueUrl = uri.getQueryParameter("continue") ?: uri.getQueryParameter("url")
                if (!continueUrl.isNullOrBlank()) {
                    currentUrl = URLDecoder.decode(continueUrl, StandardCharsets.UTF_8.name())
                    redirects++
                    continue
                }
            }

            var connection: HttpURLConnection? = null
            try {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", genericUA)
                    // Only send cookies to actual google.com domains
                    if (currentUrl.contains("google.com") || currentUrl.contains("google.co.")) {
                        setRequestProperty("Cookie", "SOCS=CAESEwgDEgk2MTQ1NzU1NDQaAmVuIAEaBgiA_LyaBg; CONSENT=YES+1")
                    }
                }

                val responseCode = connection.responseCode
                Log.d(TAG, "[Expand] Code: $responseCode")

                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location") ?: break
                    currentUrl = if (location.startsWith("http")) location else URL(URL(currentUrl), location).toString()
                    redirects++
                } else if (responseCode == 200) {
                    if (currentUrl.contains("!3d") || currentUrl.contains("!2d") || currentUrl.contains("@")) break

                    val html = connection.inputStream.bufferedReader().use { it.readText() }
                    
                    val staticMapMatcher = Pattern.compile("staticmap\\?[^\"']*center=([+-]?\\d+\\.\\d+)%2C([+-]?\\d+\\.\\d+)", Pattern.CASE_INSENSITIVE).matcher(html)
                    val ogUrlMatcher = Pattern.compile("<meta\\s+property=\"og:url\"\\s+content=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(html)
                    
                    val candidate = when {
                        staticMapMatcher.find() -> {
                            val lat = staticMapMatcher.group(1)
                            val lng = staticMapMatcher.group(2)
                            Log.d(TAG, "[Expand] Found static coords in HTML: $lat,$lng")
                            val sep = if (currentUrl.contains("?")) "&" else "?"
                            "$currentUrl${sep}direct_coords=$lat,$lng"
                        }
                        ogUrlMatcher.find() && ogUrlMatcher.group(1) != currentUrl -> ogUrlMatcher.group(1)
                        else -> null
                    }

                    if (candidate != null && candidate != currentUrl) {
                        currentUrl = candidate
                        redirects++
                        if (candidate.contains("direct_coords=")) break
                        continue
                    }
                    break
                } else {
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Expand] Error: ${e.message}")
                break
            } finally {
                connection?.disconnect()
            }
        }
        return currentUrl
    }

    private suspend fun resolveLocationString(raw: String): GpsPoint? {
        val decoded = raw.replace("+", " ") // Basic URL space cleanup
        val trimmed = decoded.trim().removeSurrounding("(", ")").removeSurrounding("[", "]").trim()
        Log.d(TAG, "[Resolve] String: '$trimmed'")

        if (trimmed.isEmpty() || trimmed.contains("Current Location", true) || trimmed.contains("My Location", true)) return null

        // 1. Direct Coords
        val latLngMatcher = LAT_LNG_REGEX.matcher(trimmed)
        if (latLngMatcher.matches()) {
            val lat = latLngMatcher.group(1)?.toDoubleOrNull()
            val lng = latLngMatcher.group(5)?.toDoubleOrNull()
            if (lat != null && lng != null) return GpsPoint(lat, lng)
        }

        // 2. Plus Code
        // Use the RAW string for Plus Code to avoid space cleanup issues
        val plusMatcher = PLUS_CODE_REGEX.matcher(raw)
        if (plusMatcher.find()) {
            val codeStr = plusMatcher.group(1)!!.uppercase()
            Log.d(TAG, "[Resolve] Detected Plus Code: $codeStr")
            try {
                val olc = OpenLocationCode(codeStr)
                if (olc.isFull) return olc.decode().let { GpsPoint(it.centerLatitude, it.centerLongitude) }

                if (olc.isShort) {
                    val locality = if (trimmed.contains(",")) trimmed.substringAfterLast(",").trim() else trimmed.replace(codeStr, "").trim()
                    Log.d(TAG, "[Resolve] Short Code. Recovery locality: '$locality'")
                    if (locality.isNotBlank()) {
                        val refPoint = geocodeAddress(locality)
                        if (refPoint != null) {
                            val fullCode = olc.recover(refPoint.latitude, refPoint.longitude)
                            Log.d(TAG, "[Resolve] Recovered: ${fullCode.code}")
                            return fullCode.decode().let { GpsPoint(it.centerLatitude, it.centerLongitude) }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Geocoder
        Log.d(TAG, "[Resolve] Geocoder fallback for: '$trimmed'")
        return geocodeAddress(trimmed)
    }

    private fun extractProtobufCoordinates(url: String): GpsPoint? {
        val placeMatcher = PROTOBUF_PLACE_PIN_REGEX.matcher(url)
        if (placeMatcher.find()) {
            val lat = placeMatcher.group(1)?.toDoubleOrNull()
            val lng = placeMatcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) return GpsPoint(lat, lng)
        }

        val dirMatcher = PROTOBUF_DIR_PIN_REGEX.matcher(url)
        if (dirMatcher.find()) {
            val lng = dirMatcher.group(1)?.toDoubleOrNull()
            val lat = dirMatcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) return GpsPoint(lat, lng)
        }
        return null
    }

    private fun extractViewportCoordinates(url: String): GpsPoint? {
        val matcher = VIEWPORT_REGEX.matcher(url)
        if (matcher.find()) {
            val lat = matcher.group(1)?.toDoubleOrNull()
            val lng = matcher.group(2)?.toDoubleOrNull()
            if (lat != null && lng != null) return GpsPoint(lat, lng)
        }
        return null
    }

    private suspend fun geocodeAddress(locationName: String): GpsPoint? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(locationName, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        continuation.resume(addresses.firstOrNull()?.let { GpsPoint(it.latitude, it.longitude) })
                    }
                    override fun onError(errorMessage: String?) { continuation.resume(null) }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            try { geocoder.getFromLocationName(locationName, 1)?.firstOrNull()?.let { GpsPoint(it.latitude, it.longitude) } } 
            catch (_: Exception) { null }
        }
    }

    private fun decodeUrlSegment(segment: String): String {
        return try {
            // URLDecoder correctly handles both %XX and + for spaces
            URLDecoder.decode(segment, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            segment
        }
    }
}
