package com.vitalis.core

/** Small builders so tests read as intent, not field soup. */
object TestSignals {
    fun validFrontal(
        t: Long,
        ratio: Float = 0.4f,
        yaw: Float = 0f,
        pitch: Float = 0f,
        leftEye: Float = 0.9f,
        rightEye: Float = 0.9f,
        smile: Float = 0.1f,
        brightness: Float = 0.6f
    ) = FaceSignals(
        faceCount = 1,
        boundingBoxRatio = ratio,
        yawDegrees = yaw,
        pitchDegrees = pitch,
        rollDegrees = 0f,
        leftEyeOpenProbability = leftEye,
        rightEyeOpenProbability = rightEye,
        smilingProbability = smile,
        brightness = brightness,
        timestampMs = t
    )

    fun noFace(t: Long) = FaceSignals.empty(t)

    fun multiFace(t: Long) = validFrontal(t).copy(faceCount = 2)
}

/** Collects engine events across many frames for concise assertions. */
class Recorder {
    val events = mutableListOf<EngineEvent>()
    fun feed(list: List<EngineEvent>) { events.addAll(list) }
    fun states() = events.filterIsInstance<EngineEvent.StateChanged>().map { it.state }
    fun result() = events.lastOrNull { it is EngineEvent.Verified || it is EngineEvent.Rejected }
}
