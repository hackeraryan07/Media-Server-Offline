package com.example

import android.content.Context
import android.util.Log
import com.example.server.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiHelper {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun generatePlaylist(context: Context, prompt: String): List<String> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        val model = prefs.getString("gemini_model_name", "gemini-3.5-flash") ?: "gemini-3.5-flash"

        if (apiKey.isBlank()) {
            throw Exception("API Key is missing. Please add it in Settings.")
        }

        // Get video list
        val videos = ServerManager.localVideoServer?.getVideosList() ?: emptyList<com.example.server.LocalVideoServer.SharedVideo>()
        if (videos.isEmpty()) {
            throw Exception("No videos available to create a playlist.")
        }

        val videoListJson = JSONArray()
        videos.forEach { video ->
            val obj = JSONObject()
            obj.put("id", video.id)
            obj.put("name", video.title)
            videoListJson.put(obj)
        }

        val systemPrompt = """
            You are an AI that creates video playlists. 
            The user wants a playlist based on their prompt.
            Here is the list of available videos in JSON format (array of objects with 'id' and 'name').
            $videoListJson
            
            Return ONLY a valid JSON array of string IDs representing the videos that match the user's prompt, 
            ordered as requested. Do not include markdown codeblocks, just the JSON array. Example: ["id1", "id2"]
        """.trimIndent()

        val jsonRequest = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }
        
        val requestBody = jsonRequest.toString().toRequestBody("application/json".toMediaType())
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${apiKey}"
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
            
        val response = try {
            client.newCall(request).execute()
        } catch (e: java.net.UnknownHostException) {
            throw Exception("Network Error: Unable to resolve host. Please check your internet connection. (${e.message})")
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            throw Exception("SSL Error: Handshake failed. The device might not support the required TLS version. (${e.message})")
        } catch (e: java.io.IOException) {
            throw Exception("Network Error: ${e.javaClass.simpleName} - ${e.message}")
        }
        
        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: ""
            Log.e("AiHelper", "API Error body: $errorBody")
            if (response.code == 403) {
                throw Exception("Error 403: Forbidden. API Key invalid/restricted.\nDetails: $errorBody")
            }
            if (response.code == 503) {
                throw Exception("Error 503: Service Unavailable. Server overloaded.\nDetails: $errorBody")
            }
            throw Exception("API Error: ${response.code} ${response.message}\n$errorBody")
        }
        
        val responseBodyString = response.body?.string() ?: throw Exception("Empty response from API")
        val jsonResponse = JSONObject(responseBodyString)
        val candidates = jsonResponse.optJSONArray("candidates")
        val text = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
            ?: throw Exception("Invalid response format from Gemini")
            
        // Text should be a JSON array string
        val resultIds = mutableListOf<String>()
        try {
            val jsonArray = JSONArray(text)
            for (i in 0 until jsonArray.length()) {
                resultIds.add(jsonArray.getString(i))
            }
        } catch (e: Exception) {
            Log.e("AiHelper", "Error parsing array: $text", e)
            throw Exception("AI did not return a valid list of IDs.")
        }
        
        resultIds
    }
}
