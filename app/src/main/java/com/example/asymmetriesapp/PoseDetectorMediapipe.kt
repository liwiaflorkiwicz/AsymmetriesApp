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
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark as TaskNormalizedLandmark // Alias for the tasks container landmark
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Wrapper class for MediaPipe's PoseLandmarker, handling asynchronous pose detection,
 * landmark processing, and recording keypoint data to a CSV file.
 */
class PoseDetectorMediapipe(context: Context? = null) {

    private var poseLandmarker: PoseLandmarker? = null // The MediaPipe detector instance
    // Holds the latest detected pose landmarks in protobuf format
    var currentPoseLandmarks: NormalizedLandmarkList? = null
        private set // Private setter limits external modification

    // Recording CSV
    private var csvWriter: BufferedWriter? = null // Writer for the CSV file
    private var isRecording = false // Flag indicating if data recording is active
    private var currentExerciseType: String = "POSE" // The type of exercise being recorded

    // Frames counter
    private val frameCounter = AtomicLong(0L)

    // Landmark pairs + names for asymmetry calculation (must match MLKit structure for analysis)
    private val asymmetryPairs = listOf(
        Pair(RIGHT_SHOULDER, LEFT_SHOULDER) to "shoulder",
        Pair(RIGHT_HIP, LEFT_HIP) to "hip",
        Pair(RIGHT_KNEE, LEFT_KNEE) to "knee",
        Pair(RIGHT_ANKLE, LEFT_ANKLE) to "ankle",
        Pair(RIGHT_ELBOW, LEFT_ELBOW) to "elbow",
        Pair(RIGHT_WRIST, LEFT_WRIST) to "wrist",
        Pair(RIGHT_EAR, LEFT_EAR) to "ear"
    )

    /**
     * Constructor block. Initializes the PoseLandmarker if a context is provided.
     * Input: Context?
     * Output: Unit (Initialization of detector)
     */
    init {
        if (context == null) {
            Log.e("PoseDetectorMP", "Context is required for Mediapipe initialization.")
        } else {
            setupPoseLandmarker(context)
        }
    }

    /**
     * Configures and creates the MediaPipe PoseLandmarker instance.
     * Uses LIVE_STREAM running mode for asynchronous real-time detection.
     * Input: Context
     * Output: Unit (Detector setup)
     */
    private fun setupPoseLandmarker(context: Context) {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("pose_landmarker_full.task") // Specify the model file
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.LIVE_STREAM) // Use live stream mode for camera frames
                .setResultListener(this::handleDetectionResult) // Callback for successful detection
                .setErrorListener(this::handleDetectionError) // Callback for detection errors
                .build()

            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
            Log.d("PoseDetectorMP", "PoseLandmarker initialized")
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "Failed to set up PoseLandmarker: ${e.message}")
        }
    }

    /**
     * Detect pose in the given [ImageProxy] (camera frame) asynchronously.
     *
     * Input: ImageProxy (camera frame data)
     * Output: Unit (Submits detection request to MediaPipe)
     */
    fun detectPose(
        imageProxy: ImageProxy
    ) {
        if (poseLandmarker == null) {
            Log.e("PoseDetectorMP", "PoseLandmarker not initialized.")
            imageProxy.close()
            return
        }

        // Convert ImageProxy to Bitmap for MediaPipe processing
        val bitmap = try {
            imageProxy.toBitmap()
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "Failed to convert ImageProxy to Bitmap: ${e.message}")
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val timestamp = imageProxy.imageInfo.timestamp
        imageProxy.close() // Close the proxy immediately after extracting data

        // Build MediaPipe image (MPImage) from the Bitmap
        val mpImage = try {
            BitmapImageBuilder(bitmap).build()
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "Failed to build MPImage: ${e.message}")
            return
        }

        try {
            val timestampMs = timestamp / 1_000_000L // Convert nanoseconds to milliseconds

            // Set image processing options, including rotation
            val imageProcessingOptions = ImageProcessingOptions.builder()
                .setRotationDegrees(rotation)
                .build()

            // Perform asynchronous detection
            poseLandmarker?.detectAsync(mpImage, imageProcessingOptions, timestampMs)
        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "detectPose: detection failed: ${e.message}")
            try { mpImage.close() } catch (_: Exception) {} // Ensure MPImage is closed on failure
        }
    }

    /**
     * Handles the successful detection result callback from MediaPipe.
     * Updates the current pose and writes a data row to the CSV file if recording is active.
     *
     * Input:
     * - result: PoseLandmarkerResult (contains detected landmarks)
     * - image: MPImage (the processed image, should be closed)
     * Output: Unit (Updates internal state and writes to CSV)
     */
    private fun handleDetectionResult(result: PoseLandmarkerResult, image: com.google.mediapipe.framework.image.MPImage) {
        try {
            val allPoses: List<List<TaskNormalizedLandmark>> = result.landmarks()
            if (allPoses.isEmpty()) {
                Log.d("PoseDetectorMP", "No poses detected.")
                this.currentPoseLandmarks = null
                return
            }

            // Process only the first detected pose
            val firstPose: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark> = allPoses[0]

            // Convert the MediaPipe Task Landmark format to the Protobuf format for compatibility/storage
            val protoBuilder = NormalizedLandmarkList.newBuilder()
            for (lm in firstPose) {
                val pb = NormalizedLandmark.newBuilder()
                    .setX(lm.x())
                    .setY(lm.y())
                    .setZ(lm.z())
                    .setVisibility(lm.visibility().orElse(0.0f)) // Get visibility, default to 0.0f
                protoBuilder.addLandmark(pb)
            }
            val landmarkListProto = protoBuilder.build()
            this.currentPoseLandmarks = landmarkListProto // Store the latest pose

            // If recording, write CSV row
            if (isRecording && csvWriter != null) {
                try {
                    val ts = System.currentTimeMillis()

                    val rowBuilder = StringBuilder()
                    rowBuilder.append("$ts,$currentExerciseType,")

                    // 1. Asymmetry Columns (Height Difference)
                    for ((pair, _) in asymmetryPairs) {
                        if (isFrontAnalysis()) {
                            val left = landmarkListProto.landmarkList.getOrNull(pair.first)
                            val right = landmarkListProto.landmarkList.getOrNull(pair.second)

                            if (left != null && right != null &&
                                isLandmarkVisible(left) && isLandmarkVisible(right)) {
                                // For MediaPipe drawing, Y is typically used as the horizontal axis (X) and X as the vertical axis (Y)
                                // We use the X coordinate (which maps to screen Y/vertical position after correction) for height difference
                                val leftY = left.x
                                val rightY = right.x
                                // Calculate height difference in normalized units (0-1) and scale to percentage (x100)
                                val heightDiff = abs(leftY - rightY) * 100f

                                rowBuilder.append("$heightDiff,")
                            } else {
                                rowBuilder.append("NaN,")
                            }
                        } else {
                            // Side analysis - asymmetry is not calculated, fill with NaN
                            rowBuilder.append("NaN,")
                        }
                    }

                    // 2. Angle Columns (Squat/Plank)
                    if (isSideAnalysis()) {
                        when (currentExerciseType) {
                            "SIDE_SQUAT" -> {
                                // Angle calculation for SIDE_SQUAT (Hip-Knee-Ankle)
                                val leftHip = getLandmark(landmarkListProto, LEFT_HIP)
                                val leftKnee = getLandmark(landmarkListProto, LEFT_KNEE)
                                val leftAnkle = getLandmark(landmarkListProto, LEFT_ANKLE)
                                val rightHip = getLandmark(landmarkListProto, RIGHT_HIP)
                                val rightKnee = getLandmark(landmarkListProto, RIGHT_KNEE)
                                val rightAnkle = getLandmark(landmarkListProto, RIGHT_ANKLE)
                                val squatAngle = when {
                                    // Use left side if visible
                                    areAllVisibleNormals(leftHip, leftKnee, leftAnkle) ->
                                        calculate3PointAngleNormals(leftHip!!, leftKnee!!, leftAnkle!!)
                                    // Otherwise, use right side if visible
                                    areAllVisibleNormals(rightHip, rightKnee, rightAnkle) ->
                                        calculate3PointAngleNormals(rightHip!!, rightKnee!!, rightAnkle!!)
                                    else -> null
                                }
                                rowBuilder.append("${squatAngle ?: "NaN"},NaN,") // Squat angle (column 1), Plank angle (column 2)
                            }
                            "PLANK" -> {
                                // Angle calculation for PLANK (Shoulder-Hip-Knee)
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
                                rowBuilder.append("NaN,${plankAngle ?: "NaN"},") // Squat angle (column 1), Plank angle (column 2)
                            }
                            else -> {
                                rowBuilder.append("NaN,NaN,")
                            }
                        }
                    } else {
                        rowBuilder.append("NaN,NaN,")
                    }

                    // 3. Raw Coordinate Columns
                    for ((pair, _) in asymmetryPairs) {
                        val left = landmarkListProto.landmarkList.getOrNull(pair.first)
                        val right = landmarkListProto.landmarkList.getOrNull(pair.second)

                        // Helper to correct coordinates from MediaPipe's output to display coordinates (X <-> Y swapped)
                        fun correctedCoordinates(lm: NormalizedLandmark): Pair<Float, Float> {
                            // MediaPipe often swaps X and Y coordinates relative to a standard image library output
                            val drawX = lm.y
                            val drawY = lm.x
                            return drawX to drawY
                        }

                        // Write left landmark coordinates
                        if (left != null) {
                            val (cx, cy) = correctedCoordinates(left)
                            // Use Locale.US for decimal points in CSV
                            rowBuilder.append(String.format(java.util.Locale.US, "%.6f,%.6f,", cx, cy))
                        } else {
                            rowBuilder.append("NaN,NaN,")
                        }

                        // Write right landmark coordinates
                        if (right != null) {
                            val (cx, cy) = correctedCoordinates(right)
                            rowBuilder.append(String.format(java.util.Locale.US, "%.6f,%.6f,", cx, cy))
                        } else {
                            rowBuilder.append("NaN,NaN,")
                        }
                    }

                    rowBuilder.append("\n")
                    csvWriter!!.write(rowBuilder.toString())
                    csvWriter!!.flush() // Flush to ensure data is written to disk promptly

                } catch (e: Exception) {
                    Log.e("PoseDetectorMP", "Failed writing CSV row: ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e("PoseDetectorMP", "handleDetectionResult: failed to process result: ${e.message}")
        } finally {
            try {
                image.close() // MUST close the MPImage to release resources
            } catch (e: Exception) {
                Log.w("PoseDetectorMP", "Failed to close MPImage: ${e.message}")
            }
        }
    }

    /**
     * Handles errors reported by the MediaPipe detector.
     * Input: Exception
     * Output: Unit (Logs error)
     */
    private fun handleDetectionError(e: Exception) {
        Log.e("PoseDetectorMP", "Detection error: ${e.message}")
    }

    /**
     * Get the current detected pose landmarks.
     * Output: NormalizedLandmarkList?
     */
    fun getCurrentPose(): NormalizedLandmarkList? = currentPoseLandmarks

    /**
     * Check if pose is visible based on key body parts (ear, wrist, ankle).
     * Input: NormalizedLandmarkList (the current pose)
     * Output: Boolean (True if minimum keypoints are visible)
     */
    fun isPoseVisible(landmarkList: NormalizedLandmarkList): Boolean {
        val landmarks = landmarkList.landmarkList
        // Check for at least one ear
        val hasEar = isLandmarkVisible(landmarks.getOrNull(LEFT_EAR)) || isLandmarkVisible(landmarks.getOrNull(RIGHT_EAR))

        // Check for at least one wrist
        val hasWrist = isLandmarkVisible(landmarks.getOrNull(LEFT_WRIST)) || isLandmarkVisible(landmarks.getOrNull(RIGHT_WRIST))

        // Check for at least one foot (ankle)
        val hasFoot = isLandmarkVisible(landmarks.getOrNull(LEFT_ANKLE)) || isLandmarkVisible(landmarks.getOrNull(RIGHT_ANKLE))

        return hasEar && hasWrist && hasFoot // Requires all three groups to have at least one visible point
    }

    /**
     * Opens a CSV file and writes the header row, initiating data recording.
     *
     * Input:
     * - file: File (The file to write to)
     * - exerciseType: String (The name of the exercise)
     * Output: Unit (Starts writing to CSV)
     */
    fun startSavingKeypoints(file: File, exerciseType: String) {
        try {
            currentExerciseType = exerciseType
            // Initialize BufferedWriter, overwriting (false) the file if it exists
            csvWriter = BufferedWriter(FileWriter(file, false))

            // header: timestamp,exercise_type, <asymmetry cols>, squat_angle,plank_angle, <coords...>
            val headerBuilder = StringBuilder()
            headerBuilder.append("timestamp,exercise_type,")

            // asymmetry columns (names)
            asymmetryPairs.forEach { (_, name) -> headerBuilder.append("${name}_height_diff,") }

            // angle columns
            headerBuilder.append("squat_angle,plank_angle,")

            // coordinate columns left/right for each asymmetry pair (raw normalized data)
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
     * Stops data recording and closes the CSV file writer.
     * Output: Unit (Stops writing and closes resources)
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
     * Placeholder method to align with the required analysis flow (returns the path of the raw data).
     * Input: File (the recorded CSV file)
     * Output: String (the absolute path of the file)
     */
    fun analyzeRecordedData(file: File): String {
        return file.absolutePath
    }

    /**
     * Helper: get landmark by index, or return null if index is out of bounds.
     * Input:
     * - list: NormalizedLandmarkList
     * - index: Int (the landmark index)
     * Output: NormalizedLandmark?
     */
    private fun getLandmark(list: NormalizedLandmarkList, index: Int): NormalizedLandmark? =
        list.landmarkList.getOrNull(index)

    /**
     * Checks if a landmark is non-null and its visibility score is above the threshold (0.7f).
     * Input: NormalizedLandmark?
     * Output: Boolean
     */
    private fun isLandmarkVisible(landmark: NormalizedLandmark?): Boolean {
        return landmark != null && landmark.visibility > 0.7f
    }

    /**
     * Checks if all provided landmarks are visible (non-null and visibility > 0.7f).
     * Input: vararg NormalizedLandmark?
     * Output: Boolean
     */
    private fun areAllVisibleNormals(vararg lm: NormalizedLandmark?): Boolean {
        return lm.all { it != null && isLandmarkVisible(it) }
    }

    /**
     * Calculates the angle (in degrees) formed by three points A-B-C, with the angle centered at B.
     * Uses normalized coordinates scaled up (x1000f) to act as pseudo-pixels for distance calculation.
     *
     * Input:
     * - pointA, pointB, pointC: NormalizedLandmark
     * Output: Float? (Angle in degrees, or null if calculation fails)
     */
    private fun calculate3PointAngleNormals(pointA: NormalizedLandmark, pointB: NormalizedLandmark, pointC: NormalizedLandmark): Float? {
        // Convert normalized coordinates (0-1) to a larger scale (pseudo-pixels) to work with angle formula
        val ax = pointA.x * 1000f
        val ay = pointA.y * 1000f
        val bx = pointB.x * 1000f
        val by = pointB.y * 1000f
        val cx = pointC.x * 1000f
        val cy = pointC.y * 1000f

        // Vector B to A
        val abx = ax - bx
        val aby = ay - by
        // Vector B to C
        val cbx = cx - bx
        val cby = cy - by

        val dot = abx * cbx + aby * cby // Dot product of vectors BA and BC
        val magAB = sqrt((abx * abx + aby * aby).toDouble()) // Magnitude of vector BA
        val magCB = sqrt((cbx * cbx + cby * cby).toDouble()) // Magnitude of vector BC

        if (magAB == 0.0 || magCB == 0.0) return null // Avoid division by zero

        // Calculate cosine of the angle using the dot product formula
        val cosAngle = (dot / (magAB * magCB)).toFloat().coerceIn(-1.0f, 1.0f)
        val angleRad = acos(cosAngle.toDouble()) // Angle in radians
        return Math.toDegrees(angleRad).toFloat() // Convert to degrees
    }

    /**
     * Determine if the current exercise requires front-facing camera analysis (for asymmetry).
     * Output: Boolean
     */
    private fun isFrontAnalysis(): Boolean = currentExerciseType in listOf("POSE", "SQUAT", "HAND_RISE")

    /**
     * Determine if the current exercise requires side-view analysis (for angle calculation).
     * Output: Boolean
     */
    private fun isSideAnalysis(): Boolean = currentExerciseType in listOf("SIDE_SQUAT", "PLANK")

    /**
     * Close MediaPipe resources and stop recording.
     * Output: Unit (Resource cleanup)
     */
    fun close() {
        try {
            poseLandmarker?.close() // Close the MediaPipe detector
        } catch (e: Exception) {
            Log.w("PoseDetectorMP", "close: ${e.message}")
        }
        // Attempt to close the CSV writer
        try {
            csvWriter?.close()
        } catch (e: Exception) {
            // ignore
        }
        isRecording = false
    }

    // --- Companion object for MediaPipe landmark indices ---
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