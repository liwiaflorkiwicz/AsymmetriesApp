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

/**
 * Wrapper class for the Google ML Kit Pose Detector.
 * It handles the configuration of the detector, real-time image processing,
 * data recording (CSV), and calculation of essential metrics (angles, asymmetry).
 */
class PoseDetectorMLKit {
    // Configuration options for the ML Kit Pose Detector
    private val options = PoseDetectorOptions.Builder()
        .setDetectorMode(PoseDetectorOptions.STREAM_MODE) // Optimized for video stream processing
        .build()

    private val poseDetector = PoseDetection.getClient(options) // The actual detector instance
    private var csvWriter: FileWriter? = null // Writer for saving keypoint data
    private var isRecording = false // Flag indicating if data recording is active
    private var currentPose: Pose? = null // Holds the most recently detected pose
    private var exerciseType: String = "POSE" // Current exercise type
    private var normalizationScale: Float = 1.0f // Scale factor used to normalize asymmetry (distance between mid-hip and mid-shoulder)

    private fun isFrontAnalysis() = exerciseType in listOf("POSE", "SQUAT", "HAND_RISE") // Helper to check for front-facing exercises
    private fun isSideAnalysis() = exerciseType in listOf("SIDE_SQUAT", "PLANK") // Helper to check for side-facing exercises

    // Landmark pairs used for bilateral asymmetry calculation, matching ML Kit indices
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
     * Get the current detected pose.
     * Output: Pose?
     */
    fun getCurrentPose(): Pose? = currentPose

    /**
     * Check if pose is visible - requires at least one ear, wrist, and foot.
     * This is used during the countdown phase to verify proper body positioning.
     *
     * Input: Pose (the detected pose)
     * Output: Boolean (True if minimum keypoints are visible)
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
     * Detect pose in the given image and handle success/failure via callbacks.
     *
     * Input:
     * - image: InputImage (the frame to process)
     * - exerciseType: String (current exercise name)
     * - onSuccess: Lambda function to execute on successful detection
     * - onFailure: Lambda function to execute on detection failure
     * Output: Unit (Submits detection request)
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
                this.currentPose = pose // Update the current pose

                if (isRecording) {
                    saveKeypointsToCSV(pose) // Save data if recording is active
                }
                onSuccess(pose) // Execute success callback
            }
            .addOnFailureListener { e ->
                Log.e("PoseDetector", "detectPose: Pose detection failed - ${e.message}")
                onFailure(e) // Execute failure callback
            }
    }

    /**
     * Start saving keypoints to a CSV file. Writes the header row.
     *
     * Input:
     * - filename: File (The file to write to)
     * - exerciseType: String (The type of exercise)
     * Output: Unit (Initializes CSV writer)
     */
    fun startSavingKeypoints(filename: File, exerciseType: String) {
        try {
            this.exerciseType = exerciseType
            csvWriter = FileWriter(filename, false) // Create or overwrite the file
            isRecording = true

            // Build the CSV header string
            val header = buildString {
                append("timestamp,exercise_type,")

                // ALL asymmetry columns (normalized height differences)
                asymmetryPairs.forEach { (_, name) ->
                    append("${name}_height_diff,")
                }

                // ALL angle columns (will contain NaN if not applicable)
                append("squat_angle,plank_angle,")

                // Coordinate columns for ALL landmarks
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
     * Save the keypoints of the detected pose to the CSV file.
     *
     * Input: Pose (the detected pose)
     * Output: Unit (Writes a row to CSV)
     */
    private fun saveKeypointsToCSV(pose: Pose) {
        try {
            val timestamp = System.currentTimeMillis()
            val row = buildString {
                append("$timestamp,$exerciseType,")

                // 1. Asymmetry measurements (Height Difference)
                asymmetryPairs.forEach { (pair, _) ->
                    if (isFrontAnalysis()) {
                        val leftLandmark = pose.getPoseLandmark(pair.first)
                        val rightLandmark = pose.getPoseLandmark(pair.second)

                        if (leftLandmark != null && rightLandmark != null &&
                            isLandmarkVisible(leftLandmark) && isLandmarkVisible(rightLandmark)) {
                            // Raw pixel difference in Y-coordinate (vertical height)
                            val heightDiff = abs(leftLandmark.position.y - rightLandmark.position.y)
                            // Normalize by the calculated body scale and convert to percentage
                            val heightDiffPercent = heightDiff / normalizationScale * 100.0f
                            append("$heightDiffPercent,")
                        } else {
                            append("NaN,") // Landmark not visible
                        }
                    } else {
                        // Side analysis does not use asymmetry
                        append("NaN,")
                    }
                }

                // 2. Angle measurements (Squat/Plank)
                if (isSideAnalysis()) {
                    val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
                    val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)
                    val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
                    val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
                    val leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
                    val rightKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)
                    val leftAnkle = pose.getPoseLandmark(PoseLandmark.LEFT_ANKLE)
                    val rightAnkle = pose.getPoseLandmark(PoseLandmark.RIGHT_ANKLE)

                    // Visibility checks for side analysis angles
                    val leftSideSquatVisible = areAllVisible(leftHip, leftKnee, leftAnkle)
                    val rightSideSquatVisible = areAllVisible(rightHip, rightKnee, rightAnkle)
                    val leftPlankVisible = areAllVisible(leftShoulder, leftHip, leftKnee)
                    val rightPlankVisible = areAllVisible(rightShoulder, rightHip, rightKnee)

                    when (exerciseType) {
                        "SIDE_SQUAT" -> {
                            // Squat angle: Hip-Knee-Ankle (use the first visible side)
                            val squatAngle = when {
                                leftSideSquatVisible -> calculate3PointAngle(leftHip, leftKnee, leftAnkle)
                                rightSideSquatVisible -> calculate3PointAngle(rightHip, rightKnee, rightAnkle)
                                else -> null
                            }
                            append("${squatAngle ?: "NaN"},NaN,") // squat_angle, plank_angle
                        }
                        "PLANK" -> {
                            // Plank angle: Shoulder-Hip-Knee (use the first visible side)
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
                    // Front analysis: no angle metrics saved
                    append("NaN,NaN,")
                }

                // 3. Raw Coordinates (X, Y)
                asymmetryPairs.forEach { (pair, _) ->
                    val leftLandmark = pose.getPoseLandmark(pair.first)
                    val rightLandmark = pose.getPoseLandmark(pair.second)

                    // Left side X, Y
                    if (leftLandmark != null && isLandmarkVisible(leftLandmark)) {
                        append("${leftLandmark.position.x},${leftLandmark.position.y},")
                    } else {
                        append("NaN,NaN,")
                    }

                    // Right side X, Y
                    if (rightLandmark != null && isLandmarkVisible(rightLandmark)) {
                        append("${rightLandmark.position.x},${rightLandmark.position.y},")
                    } else {
                        append("NaN,NaN,")
                    }
                }
                append("\n") // End of the row
            }
            csvWriter?.write(row)
            csvWriter?.flush() // Write to file immediately
        } catch (e: Exception) {
            Log.e("PoseDetector", "saveKeypointsToCSV: Failed to save keypoints - ${e.message}")
        }
    }

    /**
     * Stop saving keypoints and close the CSV file writer.
     * Output: Unit (Closes file resources)
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
     * Analyze recorded data and trigger completion callback.
     * This is a placeholder that returns the file path for the subsequent analysis step.
     * Input: File (the recorded CSV file)
     * Output: String (the absolute path of the file)
     */
    fun analyzeRecordedData(file: File): String {
        return file.absolutePath
    }

    /**
     * Calculates the normalization scale based on the vertical distance (mid-shoulder to mid-hip).
     * This value is used to normalize asymmetry measurements.
     *
     * Input: Pose (the reference pose)
     * Output: Float (Calculated distance in pixels, or 1.0f fallback)
     */
    fun calculateScale(pose: Pose): Float {
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val leftShoulder = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER)
        val rightShoulder = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER)

        if (!areAllVisible(leftHip, rightHip, leftShoulder, rightShoulder)) {
            this.normalizationScale = 1.0f
            return 1.0f
        }

        // Calculate mid-points
        val midHipX = (leftHip!!.position.x + rightHip!!.position.x) / 2
        val midHipY = (leftHip.position.y + rightHip.position.y) / 2
        val midShoulderX = (leftShoulder!!.position.x + rightShoulder!!.position.x) / 2
        val midShoulderY = (leftShoulder.position.y + rightShoulder.position.y) / 2

        // Calculate the Euclidean distance between mid-shoulder and mid-hip
        val dx = midShoulderX - midHipX
        val dy = midShoulderY - midHipY
        val scale = sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        if (scale > 10.0f) { // Check for a meaningful distance
            this.normalizationScale = scale
            Log.d("PoseDetectorMLKit", "Normalization Scale calculated: $scale pixels")
            return scale
        } else {
            this.normalizationScale = 1.0f // Fallback
            return 1.0f
        }
    }

    /**
     * Calculate 3-point angle (like in AsymmetryAnalyzer).
     * Returns the angle (in degrees) at pointB formed by points A-B-C.
     *
     * Input:
     * - pointA, pointB, pointC: PoseLandmark?
     * Output: Float? (Angle in degrees, or null if points are missing/invisible)
     */
    private fun calculate3PointAngle(pointA: PoseLandmark?, pointB: PoseLandmark?, pointC: PoseLandmark?): Float? {
        if (pointA == null || pointB == null || pointC == null) return null

        // Vector B to A (BA)
        val ab = floatArrayOf(
            pointA.position.x - pointB.position.x,
            pointA.position.y - pointB.position.y
        )
        // Vector B to C (BC)
        val cb = floatArrayOf(
            pointC.position.x - pointB.position.x,
            pointC.position.y - pointB.position.y
        )

        // Using dot product of vectors BA and BC: A dot C = |A| * |C| * cos(theta)
        val dotProduct = ab[0] * cb[0] + ab[1] * cb[1]
        val magnitudeAB = sqrt((ab[0] * ab[0] + ab[1] * ab[1]).toDouble()) // Magnitude of BA
        val magnitudeCB = sqrt((cb[0] * cb[0] + cb[1] * cb[1]).toDouble()) // Magnitude of BC

        if (magnitudeAB == 0.0 || magnitudeCB == 0.0) return null

        val cosAngle = (dotProduct / (magnitudeAB * magnitudeCB)).toFloat()
        // Clamp value to prevent acos failure due to floating point error
        val clampedCosAngle = cosAngle.coerceIn(-1.0f, 1.0f)

        val angleRad = acos(clampedCosAngle.toDouble()) // Angle in radians
        return Math.toDegrees(angleRad).toFloat() // Convert to degrees
    }

    /**
     * Check if a landmark is visible (inFrameLikelihood > 0.7f).
     * Input: PoseLandmark?
     * Output: Boolean
     */
    private fun isLandmarkVisible(landmark: PoseLandmark?): Boolean {
        return landmark != null && landmark.inFrameLikelihood > 0.7f
    }

    /**
     * Check if all landmarks in a group are visible.
     * Input: vararg PoseLandmark?
     * Output: Boolean
     */
    private fun areAllVisible(vararg landmarks: PoseLandmark?): Boolean {
        return landmarks.all { it != null && isLandmarkVisible(it) }
    }

    /**
     * Closes the pose detector and cleans up resources.
     * Output: Unit (Resource cleanup)
     */
    fun close() {
        poseDetector.close() // Close the ML Kit detector
        try {
            csvWriter?.close()
        } catch (e: Exception) {
            // ignore
        }
        stopSavingKeypoints()
    }
}