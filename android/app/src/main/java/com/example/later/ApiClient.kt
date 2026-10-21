package com.example.later

import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// code = HTTP status (429 = rate limited), or 0 when the server couldn't be reached
class ApiException(message: String, val code: Int) : Exception(message)

object ApiClient {

    private val gson: Gson = GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .create()

    // Long timeouts: Render's free tier sleeps and can take ~50s to wake up
    private val client = OkHttpClient.Builder()
        .connectTimeout(90, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        // Every request carries the shared app key so the backend knows it's our app
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("X-App-Key", BuildConfig.APP_KEY).build())
        }
        .build()

    // Sends one (already shrunk) screenshot; the open vision model says what it is.
    suspend fun analyze(jpeg: ByteArray, today: String, weekday: String): Analysis {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("today", today)
            .addFormDataPart("weekday", weekday)
            .addFormDataPart("image", "screenshot.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val request = Request.Builder()
            .url(BuildConfig.BACKEND_URL + "analyze")
            .post(body)
            .build()
        return call(request, Analysis::class.java)
    }

    private suspend fun <T> call(request: Request, type: Class<T>): T = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw ApiException(errorDetail(text) ?: "Server error ${response.code}", response.code)
                }
                gson.fromJson(text, type)
            }
        } catch (e: IOException) {
            throw ApiException("Can't reach the Later server.", 0)
        }
    }

    // FastAPI errors look like {"detail": "..."}
    private fun errorDetail(text: String): String? = try {
        JsonParser.parseString(text).asJsonObject.get("detail")?.asString
    } catch (e: Exception) {
        null
    }
}
