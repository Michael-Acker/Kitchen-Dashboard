package com.lifedashboard.tv.data

import android.content.Context
import com.lifedashboard.tv.model.DayForecast
import com.lifedashboard.tv.model.WeatherData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Open-Meteo forecast for Falls Church, VA (38.8858, -77.1805), in
 * Fahrenheit, America/New_York timezone. No API key required.
 */
class WeatherRepository(private val context: Context) : WeatherRepo {

    // Bounded waits: a hung socket must never stall a refresh pass forever.
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun refresh(): WeatherData = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(FORECAST_URL).build()
        val body: String = try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Weather request failed: HTTP ${response.code}")
                }
                response.body?.string() ?: throw IOException("Empty weather response")
            }
        } catch (e: IOException) {
            throw e
        }
        try {
            parse(JSONObject(body))
        } catch (e: Exception) {
            throw IOException("Failed to parse weather response", e)
        }
    }

    private fun parse(root: JSONObject): WeatherData {
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")

        val times = daily.getJSONArray("time")
        val maxArr = daily.getJSONArray("temperature_2m_max")
        val minArr = daily.getJSONArray("temperature_2m_min")
        val codeArr = daily.getJSONArray("weather_code")
        val sunriseArr = daily.getJSONArray("sunrise")
        val sunsetArr = daily.getJSONArray("sunset")
        val precipArr = daily.getJSONArray("precipitation_probability_max")

        val days = minOf(times.length(), DAYS)
        val dayList = ArrayList<DayForecast>(days)
        for (i in 0 until days) {
            dayList += DayForecast(
                date = LocalDate.parse(times.getString(i)),
                highF = maxArr.getDouble(i).roundToInt(),
                lowF = minArr.getDouble(i).roundToInt(),
                weatherCode = codeArr.optInt(i, -1)
            )
        }

        return WeatherData(
            tempF = current.getDouble("temperature_2m").roundToInt(),
            weatherCode = current.optInt("weather_code", -1),
            highF = maxArr.optDouble(0, Double.NaN).takeIf { !it.isNaN() }?.roundToInt() ?: 0,
            lowF = minArr.optDouble(0, Double.NaN).takeIf { !it.isNaN() }?.roundToInt() ?: 0,
            rainChancePct = precipArr.optInt(0, 0),
            sunrise = parseLocalTime(sunriseArr.optString(0, "")),
            sunset = parseLocalTime(sunsetArr.optString(0, "")),
            daily = dayList
        )
    }

    /** Open-Meteo returns ISO local times like "2026-09-25T06:57". Null on blank/malformed. */
    private fun parseLocalTime(iso: String): LocalTime? =
        runCatching {
            if (iso.contains('T')) LocalTime.parse(iso.substringAfter('T'))
            else LocalTime.parse(iso)
        }.getOrNull()

    companion object {
        private const val DAYS = 7
        private const val FORECAST_URL =
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=38.8858&longitude=-77.1805" +
                "&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code,sunrise,sunset,precipitation_probability_max" +
                "&temperature_unit=fahrenheit" +
                "&timezone=America%2FNew_York"
    }
}
