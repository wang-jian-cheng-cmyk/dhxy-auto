package com.example.dhxyauto

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DecisionClient(private val baseUrl: String) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun decide(
        goals: List<GoalItem>,
        history: List<HistoryItem>,
        screenshotPngBytes: ByteArray,
        useMockEndpoint: Boolean
    ): DecisionResponse? {
        return try {
            val sessionId = "device-local"
            val timestampMs = System.currentTimeMillis()
            val currentGoalId = goals.firstOrNull { !it.done }?.id ?: "idle"
            val goalsJson = JSONArray().apply {
                goals.forEach {
                    put(
                        JSONObject().apply {
                            put("id", it.id)
                            put("desc", it.desc)
                            put("done", it.done)
                            put("priority", it.priority)
                        }
                    )
                }
            }
            val historyJson = JSONArray().apply {
                history.forEach {
                    put(
                        JSONObject().apply {
                            put("action", it.action)
                            put("x", it.x)
                            put("y", it.y)
                            put("result", it.result)
                        }
                    )
                }
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("session_id", sessionId)
                .addFormDataPart("timestamp_ms", timestampMs.toString())
                .addFormDataPart("current_goal_id", currentGoalId)
                .addFormDataPart("goal_list_json", goalsJson.toString())
                .addFormDataPart("history_json", historyJson.toString())
                .addFormDataPart(
                    "screenshot_file",
                    "frame.png",
                    screenshotPngBytes.toRequestBody("image/png".toMediaType())
                )
                .build()

            val endpoint = if (useMockEndpoint) "/decide/mock" else "/decide"
            val request = Request.Builder()
                .url("$baseUrl$endpoint")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                return DecisionResponse(
                    action = json.optString("action", "wait"),
                    xNorm = json.optDouble("x_norm", 0.0),
                    yNorm = json.optDouble("y_norm", 0.0),
                    swipeToXNorm = json.optDouble("swipe_to_x_norm", 0.0),
                    swipeToYNorm = json.optDouble("swipe_to_y_norm", 0.0),
                    durationMs = json.optInt("duration_ms", 120),
                    nextCaptureMs = json.optInt("next_capture_ms", 1200),
                    goalId = json.optString("goal_id", "idle"),
                    confidence = json.optDouble("confidence", 0.0),
                    reason = json.optString("reason", "")
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
