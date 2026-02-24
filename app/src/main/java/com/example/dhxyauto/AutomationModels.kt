package com.example.dhxyauto

data class GoalItem(
    val id: String,
    val desc: String,
    val done: Boolean,
    val priority: Int
)

data class HistoryItem(
    val action: String,
    val x: Int,
    val y: Int,
    val result: String
)

data class DecisionResponse(
    val action: String,
    val xNorm: Double,
    val yNorm: Double,
    val swipeToXNorm: Double,
    val swipeToYNorm: Double,
    val durationMs: Int,
    val nextCaptureMs: Int,
    val goalId: String,
    val confidence: Double,
    val reason: String
)
