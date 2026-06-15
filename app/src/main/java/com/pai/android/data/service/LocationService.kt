package com.pai.android.data.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.pai.android.data.model.LocationContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Сервис геолокации с двумя источниками:
 * 1. FusedLocationProviderClient (Google Play) — активный, самый точный
 * 2. LocationManager GPS — fallback
 *
 * Всегда проверяет возраст координат — старые данные ( > 60 сек ) отбрасываются.
 */
@Singleton
class LocationService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val locationManager: LocationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @Volatile
    private var lastLocation: LocationContext? = null

    // Кеш обратного геокодирования: координаты → адрес
    private val geocodeCache = ConcurrentHashMap<String, Triple<String, String, String>>()

    /**
     * Активный запрос GPS-координат.
     * 1. FusedLocation (Google Play)
     * 2. LocationManager активный запрос
     * 3. LocationManager last known (только свежий, < 60 сек)
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): LocationContext? {
        // 1. FusedLocation (Google Play) — активный, таймаут 30 сек
        val fused = try {
            withTimeout(15_000L) { requestFusedLocation() }
        } catch (_: Exception) { null }
        if (fused != null) {
            println("LocationService: FusedLocation, age=${(System.currentTimeMillis() - fused.time)/1000}s")
            val result = buildLocationContext(fused)
            lastLocation = result
            return result
        }

        // 1.5 FusedLocation last known (пассивно, без активного запроса)
        val fusedLast = try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            suspendCancellableCoroutine<Location?> { cont ->
                client.lastLocation
                    .addOnSuccessListener { loc -> if (!cont.isCompleted) cont.resume(loc) }
                    .addOnFailureListener { if (!cont.isCompleted) cont.resume(null) }
            }
        } catch (_: Exception) { null }
        if (fusedLast != null && (System.currentTimeMillis() - fusedLast.time) < 120_000L) {
            println("LocationService: Fused last, age=${(System.currentTimeMillis() - fusedLast.time)/1000}s")
            val result = buildLocationContext(fusedLast)
            lastLocation = result
            return result
        }

        // 2. LocationManager активный GPS-запрос, таймаут 15 сек
        val active = try {
            withTimeout(15_000L) { requestActiveGpsFix() }
        } catch (_: TimeoutCancellationException) { null }
        if (active != null && active.provider == LocationManager.GPS_PROVIDER) {
            println("LocationService: Active GPS, age=${(System.currentTimeMillis() - active.time)/1000}s")
            val result = buildLocationContext(active)
            lastLocation = result
            return result
        }

        // 3. GPS last known — только если свежий (< 60 сек)
        val gpsLast = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { null }
        if (gpsLast != null && (System.currentTimeMillis() - gpsLast.time) < 60_000L) {
            println("LocationService: GPS last known, age=${(System.currentTimeMillis() - gpsLast.time)/1000}s")
            val result = buildLocationContext(gpsLast)
            lastLocation = result
            return result
        }

        // 4. Кеш сервиса — только если свежий (< 5 мин)
        val cached = lastLocation
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < 300_000L) {
            println("LocationService: cache, age=${(System.currentTimeMillis() - cached.timestamp)/1000}s")
            return cached
        }

        // 5. Крайний fallback: GPS last known без фильтра (хоть какие-то данные)
        val anyGps = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { null }
        if (anyGps != null) {
            println("LocationService: ANY GPS last known (age=${(System.currentTimeMillis() - anyGps.time)/1000}s)")
            val result = buildLocationContext(anyGps, isFresh = false)
            lastLocation = result
            return result
        }

        // 6. IP geolocation fallback — не требует прав, работает всегда, если есть интернет
        val ipLoc = try { getLocationByIp() } catch (_: Exception) { null }
        if (ipLoc != null) {
            println("LocationService: IP geolocation successful")
            lastLocation = ipLoc
            return ipLoc
        }

        println("LocationService: no location data at all")
        return null
    }

    /**
     * Запрос через FusedLocationProviderClient (Google Play Services).
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestFusedLocation(): Location? {
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            val cancellationTokenSource = com.google.android.gms.tasks.CancellationTokenSource()
            suspendCancellableCoroutine { cont ->
                client.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                ).addOnSuccessListener { loc ->
                    if (!cont.isCompleted) cont.resume(loc)
                }.addOnFailureListener {
                    // Fallback: last known
                    client.lastLocation.addOnSuccessListener { last ->
                        if (!cont.isCompleted) cont.resume(last)
                    }.addOnFailureListener {
                        if (!cont.isCompleted) cont.resume(null)
                    }
                }
                cont.invokeOnCancellation {
                    cancellationTokenSource.cancel()
                }
            }
        } catch (e: Exception) {
            println("LocationService: FusedLocation error: ${e.message}")
            null
        }
    }

    /**
     * Активный запрос через LocationManager (для устройств без Google Play).
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestActiveGpsFix(): Location? {
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return null
        return try {
            if (Build.VERSION.SDK_INT >= 31) {
                suspendCancellableCoroutine { cont ->
                    locationManager.getCurrentLocation(
                        LocationManager.GPS_PROVIDER, null,
                        java.util.concurrent.Executors.newSingleThreadExecutor(),
                        { loc -> if (!cont.isCompleted) cont.resume(loc) }
                    )
                    cont.invokeOnCancellation { /* API 31+ не требует явной отмены */ }
                }
            } else {
                suspendCancellableCoroutine { cont ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            locationManager.removeUpdates(this)
                            if (!cont.isCompleted) cont.resume(loc)
                        }
                        @Deprecated("Deprecated")
                        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {
                            if (status == android.location.LocationProvider.OUT_OF_SERVICE) {
                                locationManager.removeUpdates(this)
                                if (!cont.isCompleted) cont.resume(null)
                            }
                        }
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, null)
                    cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
                }
            }
        } catch (e: Exception) {
            println("LocationService: Active GPS error: ${e.message}")
            null
        }
    }

    /**
     * Последнее известное местоположение (пассивно, без активного запроса).
     */
    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(): LocationContext? {
        val loc = try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { null }
        if (loc != null) {
            val result = buildLocationContext(loc, isFresh = false)
            if (lastLocation == null) lastLocation = result
            return result
        }
        return lastLocation
    }

    private suspend fun buildLocationContext(
        location: Location, isFresh: Boolean = true
    ): LocationContext {
        val addressInfo = reverseGeocode(location.latitude, location.longitude)
        val now = System.currentTimeMillis()
        return LocationContext(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            altitude = location.altitude,
            provider = location.provider ?: "",
            address = addressInfo.first,
            city = addressInfo.second,
            country = addressInfo.third,
            timestamp = now,
            isFresh = isFresh && (now - location.time) < LocationContext.FRESHNESS_THRESHOLD_MS
        )
    }

    /**
     * Обратное геокодирование с кешированием.
     */
    private fun reverseGeocode(lat: Double, lng: Double): Triple<String, String, String> {
        val key = "${lat.toInt()},${lng.toInt()}"
        geocodeCache[key]?.let { return it }

        val result = try {
            if (!Geocoder.isPresent()) return Triple("", "", "")
            val geocoder = Geocoder(context, Locale("ru"))
            val addresses: List<Address> = geocoder.getFromLocation(lat, lng, 1) ?: emptyList()
            if (addresses.isEmpty()) return Triple("", "", "")
            val addr = addresses[0]
            val fullAddress = buildString {
                val parts = listOfNotNull(
                    addr.countryName?.takeIf { it.isNotBlank() },
                    addr.adminArea?.takeIf { it.isNotBlank() },
                    addr.locality?.takeIf { it.isNotBlank() },
                    addr.thoroughfare?.takeIf { it.isNotBlank() },
                    addr.featureName?.takeIf { it.isNotBlank() }
                )
                append(parts.joinToString(", "))
            }
            Triple(fullAddress, addr.locality ?: addr.subAdminArea ?: "", addr.countryName ?: "")
        } catch (e: Exception) {
            Triple("", "", "")
        }
        geocodeCache[key] = result
        return result
    }

    fun isLocationEnabled(): Boolean {
        return try {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) { false }
    }

    fun getCachedLocation(): LocationContext? {
        val cached = lastLocation ?: return null
        val age = System.currentTimeMillis() - cached.timestamp
        return cached.copy(isFresh = age < LocationContext.FRESHNESS_THRESHOLD_MS)
    }

    /**
     * Получение местоположения по IP-адресу (без прав, работает всегда).
     * Использует ip-api.com — бесплатно, без ключа, ~45 запросов/мин.
     */
    private suspend fun getLocationByIp(): LocationContext? {
        return try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url("http://ip-api.com/json/?lang=ru&fields=status,lat,lon,city,country,query")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val json = org.json.JSONObject(body)
            if (json.getString("status") == "success") {
                val lat = json.getDouble("lat")
                val lon = json.getDouble("lon")
                val city = json.optString("city", "")
                val country = json.optString("country", "")
                println("LocationService: IP geolocation -> $city ($lat, $lon)")
                LocationContext(
                    latitude = lat,
                    longitude = lon,
                    city = city,
                    country = country,
                    provider = "ip_api",
                    address = if (city.isNotBlank() && country.isNotBlank()) "$country, $city"
                               else if (city.isNotBlank()) city
                               else country,
                    timestamp = System.currentTimeMillis(),
                    isFresh = true
                )
            } else null
        } catch (e: Exception) {
            println("LocationService: IP geolocation error: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "LocationService"
    }
}
