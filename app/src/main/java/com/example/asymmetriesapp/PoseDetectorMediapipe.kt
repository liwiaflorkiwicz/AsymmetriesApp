package com.example.asymmetriesapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark as TaskNormalizedLandmark
import com.google.mediapipe.framework.image.BitmapImageBuilder
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
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
    private var normalizationScale: Float = 1.0f

    // Frame id generator
    private val frameCounter = AtomicLong(0L)

    // Landmark pairs + names to match MLKit mapping
    private val asymmetryPairs = listOf(
        Pair(LEFT_SHOULDER, RIGHT_SHOULDER) to "shoulder",
        Pair(LEFT_HIP, RIGHT_HIP) to "hip",
        Pair(LEFT_KNEE, RIGHT_KNEE) to "knee",
        Pair(LEFT_ANKLE, RIGHT_ANKLE) to "ankle",
        Pair(LEFT_ELBOW, RIGHT_ELBOW) to "elbow",
        Pair(LEFT_WRIST, RIGHT_WRIST) to "wrist",
        Pair(LEFT_EAR, RIGHT_EAR) to "ear"
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
     * Convert media Image (YUV) to Bitmap
     */
    private fun yuvToBitmap(image: android.media.Image): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val jpegBytes = out.toByteArray()

        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }


    /**
     * Detect pose in the given image and handle success/failure via callbacks.
     */
    fun detectPose(
        mediaImage: android.media.Image,
        rotation: Int,
        timestamp: Long
    ) {
        if (poseLandmarker == null) {
            Log.e("PoseDetectorMP", "PoseLandmarker not initialized.")
            return
        }

        val bitmap = yuvToBitmap(mediaImage)

        val mpImage = try {
            BitmapImageBuilder(bitmap).build()
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "Failed to build MPImage: ${e.message}")
            return
        }

        try {
            val timestampMs = if (timestamp > 1_000_000_000L) timestamp / 1_000_000L else timestamp

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
                return
            }

            val firstPose: List<TaskNormalizedLandmark> = allPoses[0]

            val protoBuilder = NormalizedLandmarkList.newBuilder()
            for (lm in firstPose) {
                val pb = NormalizedLandmark.newBuilder()
                    .setX(lm.x())
                    .setY(lm.y())
                    .setZ(lm.z())
                    .setVisibility(1.0f) // Visibility default as 1.0f
                protoBuilder.addLandmark(pb)
            }
            val landmarkListProto = protoBuilder.build()
            this.currentPoseLandmarks = landmarkListProto

            // If recording, write CSV line(s)
            if (isRecording && csvWriter != null) {
                try {
                    // unique frame id
                    val ts = System.currentTimeMillis()

                    // timestamp,exercise_type, <asymmetry columns>, <squat_angle>,<plank_angle>, <coords...>
                    val rowBuilder = StringBuilder()
                    rowBuilder.append("$ts,$currentExerciseType,")

                    val leftShoulder = landmarkListProto.landmarkList.getOrNull(LEFT_SHOULDER)
                    val rightShoulder = landmarkListProto.landmarkList.getOrNull(RIGHT_SHOULDER)
                    val leftHip = landmarkListProto.landmarkList.getOrNull(LEFT_HIP)
                    val rightHip = landmarkListProto.landmarkList.getOrNull(RIGHT_HIP)

                    var torsoHeight = 1.0f

                    if (leftShoulder != null && rightShoulder != null && leftHip != null && rightHip != null) {
                        val midShoulderY = (leftShoulder.y + rightShoulder.y) / 2f
                        val midHipY = (leftHip.y + rightHip.y) / 2f
                        val calculatedHeight = abs(midShoulderY - midHipY)
                        if (calculatedHeight > 0.05f) {
                            torsoHeight = calculatedHeight
                        }
                    }

                    // Asymmetry percent diffs (front)
                    for ((pair, _) in asymmetryPairs) {
                        val left = landmarkListProto.landmarkList.getOrNull(pair.first)
                        val right = landmarkListProto.landmarkList.getOrNull(pair.second)

                        if (isFrontAnalysis() && left != null && right != null && isLandmarkVisible(left) && isLandmarkVisible(right)) {
                            val (smLeftY, smRightY) = smoothY(left.y, right.y)
                            val diffY = abs(smLeftY - smRightY)
                            val percentageDiff = (diffY / torsoHeight) * 100f
                            rowBuilder.append(String.format(java.util.Locale.US, "%.2f,", percentageDiff))
                        } else {
                            rowBuilder.append("NaN,")
                        }
                    }

                    // Angles: decide based on exercise type (SIDE_SQUAT / PLANK). For general case write NaN/NaN
                    if (isSideAnalysis()) {
                        when (currentExerciseType) {
                            "SIDE_SQUAT" -> {
                                val leftKnee = getLandmark(landmarkListProto, LEFT_KNEE)
                                val leftAnkle = getLandmark(landmarkListProto, LEFT_ANKLE)
                                val rightKnee = getLandmark(landmarkListProto, RIGHT_KNEE)
                                val rightAnkle = getLandmark(landmarkListProto, RIGHT_ANKLE)

                                val squatAngle = when {
                                    areAllVisibleNormals(leftHip, leftKnee, leftAnkle) -> calculate3PointAngleNormals(leftHip!!, leftKnee!!, leftAnkle!!)
                                    areAllVisibleNormals(rightHip, rightKnee, rightAnkle) -> calculate3PointAngleNormals(rightHip!!, rightKnee!!, rightAnkle!!)
                                    else -> null
                                }
                                rowBuilder.append("${squatAngle ?: "NaN"},NaN,")
                            }
                            "PLANK" -> {
                                val leftKnee = getLandmark(landmarkListProto, LEFT_KNEE)
                                val rightKnee = getLandmark(landmarkListProto, RIGHT_KNEE)

                                val plankAngle = when {
                                    areAllVisibleNormals(leftShoulder, leftHip, leftKnee) -> calculate3PointAngleNormals(leftShoulder!!, leftHip!!, leftKnee!!)
                                    areAllVisibleNormals(rightShoulder, rightHip, rightKnee) -> calculate3PointAngleNormals(rightShoulder!!, rightHip!!, rightKnee!!)
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

                    // Coordinates for all asymmetryPairs (left_x,left_y,right_x,right_y), converted to pseudo-pixels
                    for ((pair, _) in asymmetryPairs) {
                        val left = landmarkListProto.landmarkList.getOrNull(pair.first)
                        val right = landmarkListProto.landmarkList.getOrNull(pair.second)

                        if (left != null) rowBuilder.append("${left.x * 1000f},${left.y * 1000f},") else rowBuilder.append("NaN,NaN,")
                        if (right != null) rowBuilder.append("${right.x * 1000f},${right.y * 1000f},") else rowBuilder.append("NaN,NaN,")
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

    private val recentLeftYs = mutableListOf<Float>()
    private val recentRightYs = mutableListOf<Float>()
    private fun smoothY(left: Float, right: Float, window: Int = 5): Pair<Float, Float> {
        recentLeftYs.add(left)
        recentRightYs.add(right)
        if (recentLeftYs.size > window) recentLeftYs.removeAt(0)
        if (recentRightYs.size > window) recentRightYs.removeAt(0)
        return Pair(recentLeftYs.average().toFloat(), recentRightYs.average().toFloat())
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