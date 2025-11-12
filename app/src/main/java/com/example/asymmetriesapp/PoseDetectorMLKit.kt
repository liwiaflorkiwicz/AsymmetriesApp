package com.example.asymmetriesapp

import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.File
import java.io.FileWriter
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

class PoseDetectorMLKit {
    private val options = PoseDetectorOptions.Builder()
        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
        .build()

    private val poseDetector = PoseDetection.getClient(options)
    private var csvWriter: FileWriter? = null
    private var isRecording = false
    private var currentPose: Pose? = null
    private var exerciseType: String = "POSE"
    private var normalizationScale: Float = 1.0f

    private fun isFrontAnalysis() = exerciseType in listOf("POSE", "SQUAT", "HAND_RISE")
    private fun isSideAnalysis() = exerciseType in listOf("SIDE_SQUAT", "PLANK")

    private val asymmetryPairs = listOf(
        Pair(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER) to "shoulder",
        Pair(PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP) to "hip",
        Pair(PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE) to "knee",
        Pair(PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE) to "ankle",
        Pair(PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW) to "elbow",
        Pair(PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST) to "wrist",
        Pair(PoseLandmark.LEFT_EAR, PoseLandmark.RIGHT_EAR) to "ear"
    )

    /**
     * Get the current detected pose
     */
    fun getCurrentPose(): Pose? = currentPose

    // POSE DETECTION
    /**
     * Check if pose is visible - requires at least one ear, wrist, and foot
     * This is used during the countdown phase to verify proper body positioning
     */
    fun isPoseVisible(pose: Pose): Boolean {
        // Check for at least one ear
        val leftEar = pose.getPoseLandmark(PoseLandmark.LEFT_EAR)
        val rightEar = pose.getPoseLandmark(PoseLandmark.RIGHT_EAR)
        val hasEar = isLandmarkVisible(leftEar) || isLandmarkVisible(rightEar)

        // Check for at least one wrist
        val leftWrist = pose.getPoseLandmark(PoseLandmark.LEFT_WRIST)
        val rightWrist = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST)
        val hasWrist = isLandmarkVisible(leftWrist) || isLandmarkVisible(rightWrist)

        // Check for at least one foot (ankle)
        val leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
        val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)
        val hasFoot = isLandmarkVisible(leftAnkle) || isLandmarkVisible(rightAnkle)

        val isVisible = hasEar && hasWrist && hasFoot
        return isVisible
    }

    // POSE DETECTION
    /**
     * Detect pose in the given image and handle success/failure via callbacks
     */
    fun detectPose(image: InputImage,
                   exerciseType: String,
                   onSuccess: (Pose) -> Unit,
                   onFailure: (Exception) -> Unit
    ) {
        this.exerciseType = exerciseType

        poseDetector.process(image)
            .addOnSuccessListener { pose ->
                Log.d("PoseDetector", "detectPose: Pose detection successful")
                this.currentPose = pose

                if (isRecording) {
                    saveKeypointsToCSV(pose)
                }
                onSuccess(pose)
            }
            .addOnFailureListener { e ->
                Log.e("PoseDetector", "detectPose: Pose detection failed - ${e.message}")
                onFailure(e)
            }
    }

    /**
     * Start saving keypoints to a CSV file
     */
    fun startSavingKeypoints(filename: File, exerciseType: String) {
        try {
            this.exerciseType = exerciseType
            csvWriter = FileWriter(filename, false)
            isRecording = true

            val header = buildString {
                append("timestamp,exercise_type,")

                // ALL asymmetry columns (always present)
                asymmetryPairs.forEach { (_, name) ->
                    append("${name}_height_diff,")
                }

                // ALL angle columns (always present)
                append("squat_angle,plank_angle,")

                // Coordinate columns for ALL exercises
                asymmetryPairs.forEach { (_, name) ->
                    append("left_${name}_x,left_${name}_y,right_${name}_x,right_${name}_y,")
                }
                append("\n")
            }
            csvWriter?.write(header)
            csvWriter?.flush()
        } catch (e: Exception) {
            Log.e("PoseDetector", "startSavingKeypoints: Failed to start saving keypoints - ${e.message}")
            isRecording = false
        }
    }

    /**
     * Save the keypoints of the detected pose to the CSV file
     */
    private fun saveKeypointsToCSV(pose: Pose) {
        try {
            val timestamp = System.currentTimeMillis()
            val row = buildString {
                append("$timestamp,$exerciseType,")

                // Asymmetry measurements - only for front analysis
                asymmetryPairs.forEach { (pair, _) ->
                    if (isFrontAnalysis()) {
                        val leftLandmark = pose.getPoseLandmark(pair.first)
                        val rightLandmark = pose.getPoseLandmark(pair.second)

                        if (leftLandmark != null && rightLandmark != null &&
                            isLandmarkVisible(leftLandmark) && isLandmarkVisible(rightLandmark)) {
                            val heightDiff = abs(leftLandmark.position.y - rightLandmark.position.y)
                            val heightDiffPercent = heightDiff / normalizationScale * 100.0f
                            append("$heightDiffPercent,")
                        } else {
                            append("NaN,")
                        }
                    } else {
                        // Side analysis - fill with NaN
                        append("NaN,")
                    }
                }

                // Angle measurements - only relevant ones for each exercise
                if (isSideAnalysis()) {
                    val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                    val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                    val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
                    val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
                    val leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
                    val rightKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)
                    val leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
                    val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)

                    val leftSideSquatVisible = areAllVisible(leftHip, leftKnee, leftAnkle)
                    val rightSideSquatVisible = areAllVisible(rightHip, rightKnee, rightAnkle)
                    val leftPlankVisible = areAllVisible(leftShoulder, leftHip, leftKnee)
                    val rightPlankVisible = areAllVisible(rightShoulder, rightHip, rightKnee)

                    when (exerciseType) {
                        "SIDE_SQUAT" -> {
                            // Only squat angle for side squat
                            val squatAngle = when {
                                leftSideSquatVisible -> calculate3PointAngle(leftHip, leftKnee, leftAnkle)
                                rightSideSquatVisible -> calculate3PointAngle(rightHip, rightKnee, rightAnkle)
                                else -> null
                            }
                            append("${squatAngle ?: "NaN"},NaN,") // squat_angle, plank_angle
                        }
                        "PLANK" -> {
                            // Only plank angle for plank
                            val plankAngle = when {
                                leftPlankVisible -> calculate3PointAngle(leftShoulder, leftHip, leftKnee)
                                rightPlankVisible -> calculate3PointAngle(rightShoulder, rightHip, rightKnee)
                                else -> null
                            }
                            append("NaN,${plankAngle ?: "NaN"},") // squat_angle, plank_angle
                        }
                        else -> {
                            append("NaN,NaN,")
                        }
                    }
                } else {
                    // Front analysis - both angles as NaN
                    append("NaN,NaN,")
                }

                // Coordinates for all landmarks (always present)
                asymmetryPairs.forEach { (pair, _) ->
                    val leftLandmark = pose.getPoseLandmark(pair.first)
                    val rightLandmark = pose.getPoseLandmark(pair.second)

                    if (leftLandmark != null && isLandmarkVisible(leftLandmark)) {
                        append("${leftLandmark.position.x},${leftLandmark.position.y},")
                    } else {
                        append("NaN,NaN,")
                    }

                    if (rightLandmark != null && isLandmarkVisible(rightLandmark)) {
                        append("${rightLandmark.position.x},${rightLandmark.position.y},")
                    } else {
                        append("NaN,NaN,")
                    }
                }
                append("\n")
            }
            csvWriter?.write(row)
            csvWriter?.flush()
        } catch (e: Exception) {
            Log.e("PoseDetector", "saveKeypointsToCSV: Failed to save keypoints - ${e.message}")
        }
    }

    /**
     * Stop saving keypoints and close the CSV file
     */
    fun stopSavingKeypoints() {
        isRecording = false
        try {
            csvWriter?.flush()
            csvWriter?.close()
            csvWriter = null
            Log.d("PoseDetector", "stopSavingKeypoints: Keypoints saved and file closed")
        } catch (e: Exception) {
            Log.e("PoseDetector", "stopSavingKeypoints: Failed to stop saving keypoints - ${e.message}")
        }
    }

    /**
     * Analyze recorded data and trigger completion callback
     */
    fun analyzeRecordedData(file: File): String {
        return file.absolutePath
    }

    fun calculateScale(pose: Pose): Float {
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

        if (!areAllVisible(leftHip, rightHip, leftShoulder, rightShoulder)) {
            this.normalizationScale = 1.0f
            return 1.0f
        }

        val midHipX = (leftHip!!.position.x + rightHip!!.position.x) / 2
        val midHipY = (leftHip.position.y + rightHip.position.y) / 2
        val midShoulderX = (leftShoulder!!.position.x + rightShoulder!!.position.x) / 2
        val midShoulderY = (leftShoulder.position.y + rightShoulder.position.y) / 2

        val dx = midShoulderX - midHipX
        val dy = midShoulderY - midHipY
        val scale = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (scale > 10.0f) { // arbitrary minimum to avoid division by zero
            this.normalizationScale = scale
            Log.d("PoseDetectorMLKit", "Normalization Scale calculated: $scale pixels")
            return scale
        } else {
            this.normalizationScale = 1.0f
            return 1.0f
        }
    }

    /**
     * Calculate 3-point angle (like in AsymmetryAnalyzer)
     * Returns the angle at pointB formed by points A-B-C
     */
    private fun calculate3PointAngle(pointA: PoseLandmark?, pointB: PoseLandmark?, pointC: PoseLandmark?): Float? {
        if (pointA == null || pointB == null || pointC == null) return null

        val ab = floatArrayOf(
            pointB.position.x - pointA.position.x,
            pointB.position.y - pointA.position.y
        )
        val cb = floatArrayOf(
            pointB.position.x - pointC.position.x,
            pointB.position.y - pointC.position.y
        )

        val dotProduct = ab[0] * cb[0] + ab[1] * cb[1]
        val magnitudeAB = sqrt((ab[0] * ab[0] + ab[1] * ab[1]).toDouble())
        val magnitudeCB = sqrt((cb[0] * cb[0] + cb[1] * cb[1]).toDouble())

        if (magnitudeAB == 0.0 || magnitudeCB == 0.0) return null

        val cosAngle = (dotProduct / (magnitudeAB * magnitudeCB)).toFloat()
        val clampedCosAngle = cosAngle.coerceIn(-1.0f, 1.0f)

        val angleRad = acos(clampedCosAngle.toDouble())
        return Math.toDegrees(angleRad).toFloat()
    }

    /**
     * Check if a landmark is visible (has good confidence)
     */
    private fun isLandmarkVisible(landmark: PoseLandmark?): Boolean {
        return landmark != null && landmark.inFrameLikelihood > 0.7f
    }

    /**
     * Check if all landmarks in a group are visible
     */
    private fun areAllVisible(vararg landmarks: PoseLandmark?): Boolean {
        return landmarks.all { it != null && isLandmarkVisible(it) }
    }

    fun close() {
        poseDetector.close()
        csvWriter?.close()
        stopSavingKeypoints()
    }

}

