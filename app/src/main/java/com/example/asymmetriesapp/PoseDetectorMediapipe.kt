package com.example.asymmetriesapp

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark as TaskNormalizedLandmark
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

class PoseDetectorMediapipe(context: Context? = null) {

    private var poseLandmarker: PoseLandmarker? = null
    var currentPoseLandmarks: NormalizedLandmarkList? = null
        private set

    // Recording CSV
    private var csvWriter: BufferedWriter? = null
    private var isRecording = false
    private var currentExerciseType: String = "POSE"

    // Frames counter
    private val frameCounter = AtomicLong(0L)

    // Landmark pairs + names to match MLKit mapping
    private val asymmetryPairs = listOf(
        Pair(RIGHT_SHOULDER, LEFT_SHOULDER) to "shoulder",
        Pair(RIGHT_HIP, LEFT_HIP) to "hip",
        Pair(RIGHT_KNEE, LEFT_KNEE) to "knee",
        Pair(RIGHT_ANKLE, LEFT_ANKLE) to "ankle",
        Pair(RIGHT_ELBOW, LEFT_ELBOW) to "elbow",
        Pair(RIGHT_WRIST, LEFT_WRIST) to "wrist",
        Pair(RIGHT_EAR, LEFT_EAR) to "ear"
    )

    init {
        if (context == null) {
            Log.e("PoseDetectorMP", "Context is required for Mediapipe initialization.")
        } else {
            setupPoseLandmarker(context)
        }
    }

    private fun setupPoseLandmarker(context: Context) {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_full.task")
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::handleDetectionResult)
                .setErrorListener(this::handleDetectionError)
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.d("PoseDetectorMP", "PoseLandmarker initialized")
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "Failed to set up PoseLandmarker: ${e.message}")
        }
    }

    /**
     * Detect pose in the given image and handle success/failure via callbacks.
     */
    fun detectPose(
        imageProxy: ImageProxy
    ) {
        if (poseLandmarker == null) {
            Log.e("PoseDetectorMP", "PoseLandmarker not initialized.")
            imageProxy.close()
            return
        }

        val bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "Failed to convert ImageProxy to Bitmap: ${e.message}")
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val timestamp = imageProxy.imageInfo.timestamp
        imageProxy.close()

        val mpImage = try {
            BitmapImageBuilder(bitmap).build()
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "Failed to build MPImage: ${e.message}")
            return
        }

        try {
            val timestampMs = timestamp / 1_000_000L

            val imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotation)
                .build()

            poseLandmarker?.detectAsync(mpImage, imageProcessingOptions, timestampMs)
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "detectPose: detection failed: ${e.message}")
            try { mpImage.close() } catch (_: Exception) {}
        }
    }

    private fun handleDetectionResult(result: PoseLandmarkerResult, image: com.google.mediapipe.framework.image.MPImage) {
        try {
            val allPoses: List<List<TaskNormalizedLandmark>> = result.landmarks()
            if (allPoses.isEmpty()) {
                Log.d("PoseDetectorMP", "No poses detected.")
                this.currentPoseLandmarks = null
                return
            }

            val firstPose: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> = allPoses[0]

            val protoBuilder = NormalizedLandmarkList.newBuilder()
            for (lm in firstPose) {
                val pb = NormalizedLandmark.newBuilder()
                    .setX(lm.x())
                    .setY(lm.y())
                    .setZ(lm.z())
                    .setVisibility(lm.visibility().orElse(0.0f))
                protoBuilder.addLandmark(pb)
            }
            val landmarkListProto = protoBuilder.build()
            this.currentPoseLandmarks = landmarkListProto

            // If recording, write CSV line(s)
            if (isRecording && csvWriter != null) {
                try {
                    val ts = System.currentTimeMillis()

                    val rowBuilder = StringBuilder()
                    rowBuilder.append("$ts,$currentExerciseType,")

                    // Write NaN for all height_diff columns (no calculation)
                    for ((pair, _) in asymmetryPairs) {
                        if (isFrontAnalysis()) {
                            val left = landmarkListProto.landmarkList.getOrNull(pair.first)
                            val right = landmarkListProto.landmarkList.getOrNull(pair.second)

                            if (left != null && right != null &&
                                isLandmarkVisible(left) && isLandmarkVisible(right)) {
                                // Poprawione Y zgodnie z rysowaniem
                                val leftY = left.x
                                val rightY = right.x
                                val heightDiff = abs(leftY - rightY) * 100f

                                rowBuilder.append("$heightDiff,")
                            } else {
                                rowBuilder.append("NaN,")
                            }
                        } else {
                            // Side analysis - fill with NaN
                            rowBuilder.append("NaN,")
                        }
                    }

                    // Angles: decide based on exercise type (SIDE_SQUAT / PLANK)
                    if (isSideAnalysis()) {
                        when (currentExerciseType) {
                            "SIDE_SQUAT" -> {
                                val leftHip = getLandmark(landmarkListProto, LEFT_HIP)
                                val leftKnee = getLandmark(landmarkListProto, LEFT_KNEE)
                                val leftAnkle = getLandmark(landmarkListProto, LEFT_ANKLE)
                                val rightHip = getLandmark(landmarkListProto, RIGHT_HIP)
                                val rightKnee = getLandmark(landmarkListProto, RIGHT_KNEE)
                                val rightAnkle = getLandmark(landmarkListProto, RIGHT_ANKLE)
                                val squatAngle = when {
                                    areAllVisibleNormals(leftHip, leftKnee, leftAnkle) ->
                                        calculate3PointAngleNormals(leftHip!!, leftKnee!!, leftAnkle!!)
                                    areAllVisibleNormals(rightHip, rightKnee, rightAnkle) ->
                                        calculate3PointAngleNormals(rightHip!!, rightKnee!!, rightAnkle!!)
                                    else -> null
                                }
                                rowBuilder.append("${squatAngle ?: "NaN"},NaN,")
                            }
                            "PLANK" -> {
                                val leftShoulder = getLandmark(landmarkListProto, LEFT_SHOULDER)
                                val leftHip = getLandmark(landmarkListProto, LEFT_HIP)
                                val leftKnee = getLandmark(landmarkListProto, LEFT_KNEE)
                                val rightShoulder = getLandmark(landmarkListProto, RIGHT_SHOULDER)
                                val rightHip = getLandmark(landmarkListProto, RIGHT_HIP)
                                val rightKnee = getLandmark(landmarkListProto, RIGHT_KNEE)
                                val plankAngle = when {
                                    areAllVisibleNormals(leftShoulder, leftHip, leftKnee) ->
                                        calculate3PointAngleNormals(leftShoulder!!, leftHip!!, leftKnee!!)
                                    areAllVisibleNormals(rightShoulder, rightHip, rightKnee) ->
                                        calculate3PointAngleNormals(rightShoulder!!, rightHip!!, rightKnee!!)
                                    else -> null
                                }
                                rowBuilder.append("NaN,${plankAngle ?: "NaN"},")
                            }
                            else -> {
                                rowBuilder.append("NaN,NaN,")
                            }
                        }
                    } else {
                        rowBuilder.append("NaN,NaN,")
                    }

                    // Record raw coordinates for all landmarks with detailed logging
                    for ((pair, _) in asymmetryPairs) {
                        val left = landmarkListProto.landmarkList.getOrNull(pair.first)
                        val right = landmarkListProto.landmarkList.getOrNull(pair.second)

                        fun correctedCoordinates(lm: NormalizedLandmark): Pair<Float, Float> {
                            // Zamiana x <-> y i odwrócenie pionu (zgodnie z rysowaniem)
                            val drawX = lm.y
                            val drawY = lm.x
                            return drawX to drawY
                        }

                        if (left != null) {
                            val (cx, cy) = correctedCoordinates(left)
                            rowBuilder.append(String.format(java.util.Locale.US, "%.6f,%.6f,", cx, cy))
                        } else {
                            rowBuilder.append("NaN,NaN,")
                        }

                        if (right != null) {
                            val (cx, cy) = correctedCoordinates(right)
                            rowBuilder.append(String.format(java.util.Locale.US, "%.6f,%.6f,", cx, cy))
                        } else {
                            rowBuilder.append("NaN,NaN,")
                        }

                    }


                    rowBuilder.append("\n")
                    csvWriter!!.write(rowBuilder.toString())
                    csvWriter!!.flush()

                } catch (e: Exception) {
                    Log.e("PoseDetectorMP", "Failed writing CSV row: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "handleDetectionResult: failed to process result: ${e.message}")
        } finally {
            try {
                image.close()
            } catch (e: Exception) {
                Log.w("PoseDetectorMP", "Failed to close MPImage: ${e.message}")
            }
        }
    }

    private fun handleDetectionError(e: Exception) {
        Log.e("PoseDetectorMP", "Detection error: ${e.message}")
    }

    /**
     * Get the current detected pose landmarks
     */
    fun getCurrentPose(): NormalizedLandmarkList? = currentPoseLandmarks

    /**
     * Check if pose is visible - requires at least one ear, wrist, and foot
     */
    fun isPoseVisible(landmarkList: NormalizedLandmarkList): Boolean {
        val landmarks = landmarkList.landmarkList
        // Check for at least one ear
        val hasEar = isLandmarkVisible(landmarks.getOrNull(LEFT_EAR)) || isLandmarkVisible(landmarks.getOrNull(RIGHT_EAR))

        // Check for at least one wrist
        val hasWrist = isLandmarkVisible(landmarks.getOrNull(LEFT_WRIST)) || isLandmarkVisible(landmarks.getOrNull(RIGHT_WRIST))

        // Check for at least one foot (ankle)
        val hasFoot = isLandmarkVisible(landmarks.getOrNull(LEFT_ANKLE)) || isLandmarkVisible(landmarks.getOrNull(RIGHT_ANKLE))

        return hasEar && hasWrist && hasFoot
    }

    /**
     * Start saving keypoints to CSV file (header matches MLKit structure)
     */
    fun startSavingKeypoints(file: File, exerciseType: String) {
        try {
            currentExerciseType = exerciseType
            csvWriter = BufferedWriter(FileWriter(file, false))

            // header: timestamp,exercise_type, <asymmetry cols>, squat_angle,plank_angle, <coords...>
            val headerBuilder = StringBuilder()
            headerBuilder.append("timestamp,exercise_type,")

            // asymmetry columns (names)
            asymmetryPairs.forEach { (_, name) -> headerBuilder.append("${name}_height_diff,") }

            // angle columns
            headerBuilder.append("squat_angle,plank_angle,")

            // coordinate columns left/right for each asymmetry pair
            asymmetryPairs.forEach { (_, name) ->
                headerBuilder.append("left_${name}_x,left_${name}_y,right_${name}_x,right_${name}_y,")
            }
            headerBuilder.append("\n")
            csvWriter?.write(headerBuilder.toString())
            csvWriter?.flush()
            isRecording = true
            frameCounter.set(0L)
            Log.d("PoseDetectorMP", "Started recording to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "startSavingKeypoints: ${e.message}")
            isRecording = false
            csvWriter = null
        }
    }

    /**
     * Stop saving and close CSV
     */
    fun stopSavingKeypoints() {
        isRecording = false
        try {
            csvWriter?.flush()
            csvWriter?.close()
            csvWriter = null
            Log.d("PoseDetectorMP", "Stopped recording")
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "stopSavingKeypoints: ${e.message}")
        }
    }

    /**
     * Analyze recorded data - placeholder, returns CSV absolute path to be consistent with MLKit
     */
    fun analyzeRecordedData(file: File): String {
        return file.absolutePath
    }

    /**
     * Calculate normalization scale similar to MLKit: distance between mid-shoulder and mid-hip in pseudo-pixels
     * Input: NormalizedLandmarkList (values 0..1) -> convert to pseudo-pixels *1000f
     */
    fun calculateScale(pose: NormalizedLandmarkList): Float {
        val leftHip = pose.landmarkList.getOrNull(LEFT_HIP) ?: return 1.0f
        val rightHip = pose.landmarkList.getOrNull(RIGHT_HIP) ?: return 1.0f
        val leftShoulder = pose.landmarkList.getOrNull(LEFT_SHOULDER) ?: return 1.0f
        val rightShoulder = pose.landmarkList.getOrNull(RIGHT_SHOULDER) ?: return 1.0f

        val midHipY = (leftHip.y + rightHip.y) / 2f
        val midShoulderY = (leftShoulder.y + rightShoulder.y) / 2f

        return abs(midShoulderY - midHipY)
    }

    /**
     * Helper: get landmark or null
     */
    private fun getLandmark(list: NormalizedLandmarkList, index: Int): NormalizedLandmark? =
        list.landmarkList.getOrNull(index)

    private fun isLandmarkVisible(landmark: NormalizedLandmark?): Boolean {
        return landmark != null && landmark.visibility > 0.7f
    }

    private fun areAllVisibleNormals(vararg lm: NormalizedLandmark?): Boolean {
        return lm.all { it != null && isLandmarkVisible(it) }
    }

    /**
     * Calculate 3-point angle for normalized landmarks (converted to pseudo-pixels)
     * Angle at pointB formed by A-B-C
     */
    private fun calculate3PointAngleNormals(pointA: NormalizedLandmark, pointB: NormalizedLandmark, pointC: NormalizedLandmark): Float? {
        // convert to pseudo-pixels
        val ax = pointA.x * 1000f
        val ay = pointA.y * 1000f
        val bx = pointB.x * 1000f
        val by = pointB.y * 1000f
        val cx = pointC.x * 1000f
        val cy = pointC.y * 1000f

        val abx = bx - ax
        val aby = by - ay
        val cbx = bx - cx
        val cby = by - cy

        val dot = abx * cbx + aby * cby
        val magAB = sqrt((abx * abx + aby * aby).toDouble())
        val magCB = sqrt((cbx * cbx + cby * cby).toDouble())

        if (magAB == 0.0 || magCB == 0.0) return null

        val cosAngle = (dot / (magAB * magCB)).toFloat().coerceIn(-1.0f, 1.0f)
        val angleRad = acos(cosAngle.toDouble())
        return Math.toDegrees(angleRad).toFloat()
    }

    /**
     * Determine if exercise type is front analysis
     */
    private fun isFrontAnalysis(): Boolean = currentExerciseType in listOf("POSE", "SQUAT", "HAND_RISE")

    /**
     * Determine if side analysis is required
     */
    private fun isSideAnalysis(): Boolean = currentExerciseType in listOf("SIDE_SQUAT", "PLANK")

    /**
     * Close resources
     */
    fun close() {
        try {
            poseLandmarker?.close()
        } catch (e: Exception) {
            Log.w("PoseDetectorMP", "close: ${e.message}")
        }
        try {
            csvWriter?.close()
        } catch (e: Exception) {
            // ignore
        }
        isRecording = false
    }

    companion object {
        const val LEFT_EAR = 7
        const val RIGHT_EAR = 8
        const val LEFT_WRIST = 15
        const val RIGHT_WRIST = 16
        const val LEFT_ANKLE = 27
        const val RIGHT_ANKLE = 28
        const val LEFT_SHOULDER = 11
        const val RIGHT_SHOULDER = 12
        const val LEFT_HIP = 23
        const val RIGHT_HIP = 24
        const val LEFT_KNEE = 25
        const val RIGHT_KNEE = 26
        const val LEFT_ELBOW = 13
        const val RIGHT_ELBOW = 14
    }
}