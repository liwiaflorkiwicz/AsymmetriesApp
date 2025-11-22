package com.example.asymmetriesapp

import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import android.Manifest
import android.R
import android.content.Intent
import android.graphics.Color
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import com.example.asymmetriesapp.databinding.ActivityMainBinding
import com.google.common.util.concurrent.ListenableFuture
// mlkit imports
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
// mediapipe imports
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Main Activity for real-time pose analysis and data recording.
 * It handles camera setup, permission requests, pose detection (ML Kit or MediaPipe),
 * exercise flow (countdown, recording), and database integration for saving results.
 */
class MainActivity : AppCompatActivity() {
    private enum class DetectionModel { MLKIT, MEDIAPIPE } // Enum for selecting the detection backend

    private val model = DetectionModel.MEDIAPIPE // Currently selected model

    private lateinit var viewBinding: ActivityMainBinding // View binding instance
    private lateinit var cameraExecutor: ExecutorService // Executor for running camera tasks
    private lateinit var imageAnalyzer: ImageAnalysis // Analyzer for processing camera frames
    private var poseDetectorMLKit: PoseDetectorMLKit? = null     // ML Kit Pose Detector wrapper
    private var poseDetectorMediapipe: PoseDetectorMediapipe? = null // Mediapipe Pose Detector wrapper
    private lateinit var db: AppDatabase // Room database instance

    private var exerciseType: String = "POSE" // Default exercise type, retrieved from Intent
    private var isFrontAnalysis: Boolean = true // Flag indicating if the analysis is from the front view

    private enum class RecordingState {
        IDLE,           // Initial state - button shows "Start Exercise"
        COUNTDOWN,      // 10 second countdown checking pose visibility
        RECORDING,      // 20 second recording data
        READY_FOR_RESULTS  // Recording complete, ready to generate results
    }

    private var currentState = RecordingState.IDLE // Current state of the exercise flow
    private var countdownJob: Job? = null // Coroutine job for the countdown
    private var recordingJob: Job? = null // Coroutine job for the recording phase
    private var remainingTime = 20 // seconds remaining in recording
    private var csvFile: File? = null // File to save recorded keypoint data

    /**
     * Initializes the activity, sets up the UI, initializes the detection model,
     * requests permissions, and starts the camera.
     * Input: Bundle? (savedInstanceState)
     * Output: Unit (Activity initialization)
     */
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        Log.d(TAG, "onCreate: START")
        super.onCreate(savedInstanceState)

        try {
            // Get exercise type from intent
            exerciseType = intent.getStringExtra("extra_exercise_type") ?: "POSE"
            // Determine the camera view required based on exercise type
            isFrontAnalysis = exerciseType in listOf("POSE", "SQUAT", "HAND_RISE")

            viewBinding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(viewBinding.root)
            // Ensure status texts and buttons are visible over the camera preview
            viewBinding.textView?.bringToFront()
            viewBinding.btnStartAnalysis?.bringToFront()

            // Initialize the single-threaded executor for camera analysis
            cameraExecutor = Executors.newSingleThreadExecutor()

            // Initialize the selected pose detection model
            when (model) {
                DetectionModel.MLKIT -> {
                    poseDetectorMLKit = PoseDetectorMLKit()
                    Log.d(TAG, "Using ML Kit Pose Detector")
                }

                DetectionModel.MEDIAPIPE -> {
                    poseDetectorMediapipe = PoseDetectorMediapipe(applicationContext)
                    Log.d(TAG, "Using Mediapipe Pose Detector")
                }
            }

            // Request camera permissions
            if (allPermissionsGranted()) {
                startCameraWithAnalysis()
                setupAnalysisButton()
                resetToIdle()
            } else {
                requestPermissions()
            }

            // Build database instance
            db = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java,
                "asymmetry_db" // Database file name
            ).build()

            // Set up navigation to Menu Activity
            viewBinding.btnMenu?.setOnClickListener {
                val intent = Intent(this, MenuActivity::class.java)
                startActivity(intent)
            }

            // Set up navigation to Help Activity
            viewBinding.floatingActionButton?.setOnClickListener {
                val intent = Intent(this, HelpActivity::class.java)
                startActivity(intent)
            }

            // Update the display name of the exercise
            viewBinding.textViewExerciseName?.text = when (exerciseType) {
                "POSE" -> "Pose Analysis"
                "SQUAT" -> "Squat Analysis"
                "SIDE_SQUAT" -> "Side Squat Analysis"
                "PLANK" -> "Plank Analysis"
                "HAND_RISE" -> "Hand Rise Analysis"
                else -> "Exercise Analysis"
            }

            Log.d(TAG, "onCreate: COMPLETED SUCCESSFULLY")

        } catch (e: Exception) {
            Log.e(TAG, "onCreate: FATAL ERROR", e)
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Sets the click listener for the main control button and defines its behavior based on the current state.
     * Output: Unit (Button listener setup)
     */
    private fun setupAnalysisButton() {
        viewBinding.btnStartAnalysis?.setOnClickListener {
            Log.d(TAG, "Button pressed in state: $currentState")

            when (currentState) {
                RecordingState.IDLE -> {
                    startCountdown() // Start the 10-second pose check
                }

                RecordingState.COUNTDOWN -> {
                    cancelCountdown()   // Allow user to cancel the countdown
                }

                RecordingState.RECORDING -> {
                    completeRecording() // Allow user to manually stop recording
                }

                RecordingState.READY_FOR_RESULTS -> {
                    generateAndShowResults() // Proceed to data analysis and report
                }
            }
        }
    }

    /**
     * Starts the 10-second countdown during which the system checks for pose visibility.
     * Output: Unit (Starts a coroutine for countdown)
     */
    private fun startCountdown() {
        currentState = RecordingState.COUNTDOWN
        updateButtonUI("Cancel")

        var isPoseVisibleInLastCheck = false // Flag to check the pose visibility at the last second
        var poseForScale: Any? = null // Holds the pose object for scale calculation

        countdownJob = lifecycleScope.launch {
            for (i in 10 downTo 1) {
                if (!isActive) break // Exit if the job is cancelled

                val isPoseValid: Boolean

                // Check pose validity based on the selected model
                when (model) {
                    DetectionModel.MLKIT -> {
                        val currentPose = poseDetectorMLKit?.getCurrentPose()
                        // Check if pose is non-null and fully visible
                        isPoseValid = currentPose is Pose && poseDetectorMLKit!!.isPoseVisible(currentPose)
                        poseForScale = if (isPoseValid) currentPose else null
                    }

                    DetectionModel.MEDIAPIPE -> {
                        val mpPose = poseDetectorMediapipe?.currentPoseLandmarks
                        isPoseValid = mpPose != null && poseDetectorMediapipe!!.isPoseVisible(mpPose)
                        poseForScale = if (isPoseValid) mpPose else null
                    }
                }
                Log.d(TAG, "isPoseValid: $isPoseValid")

                isPoseVisibleInLastCheck = isPoseValid

                runOnUiThread { // Update UI on the main thread
                    if (isPoseValid) {
                        viewBinding.textView?.text = "Body detected - $i"
                        viewBinding.textView?.setTextColor(getColor(R.color.holo_green_dark))
                    } else {
                        viewBinding.textView?.text = "Position yourself - $i\nShow full body"
                        viewBinding.textView?.setTextColor(getColor(R.color.holo_orange_dark))
                    }
                }
                delay(1000) // Wait for 1 second
            }

            // After countdown completes
            runOnUiThread {
                viewBinding.textView?.text = "" // Clear status message
            }
            if (isActive) {
                if (isPoseVisibleInLastCheck) {
                    // If pose was correct at the end of countdown
                    if (poseForScale != null && isFrontAnalysis) {
                        // Calculate normalization scale if front analysis is being performed
                        if (model == DetectionModel.MEDIAPIPE) poseDetectorMLKit?.calculateScale(poseForScale as Pose)
                    }
                    startRecording() // Proceed to recording
                } else {
                    // If pose was not visible at the end
                    runOnUiThread {
                        viewBinding.textView?.text = "Couldn't detect pose during countdown. Try again."
                        viewBinding.textView?.setTextColor(getColor(R.color.holo_red_dark))
                    }
                    resetToIdle()
                }
            }
        }
    }

    /**
     * Cancels the current countdown job and resets the state to IDLE.
     * Output: Unit (Cancels coroutine and resets state)
     */
    private fun cancelCountdown() {
        countdownJob?.cancel()
        resetToIdle()
    }

    /**
     * Prepares the file, starts the recording coroutine, and instructs the pose detector to save keypoints.
     * Output: Unit (Starts data collection)
     */
    private fun startRecording() {
        // Prepare CSV file path with timestamp and exercise type
        csvFile = File(filesDir, "keypoints_${System.currentTimeMillis()}_${exerciseType}_${model}.csv")

        // Start saving CSV keypoints
        csvFile?.let { file ->
            when (model) {
                DetectionModel.MLKIT -> poseDetectorMLKit?.startSavingKeypoints(file, exerciseType)
                DetectionModel.MEDIAPIPE -> poseDetectorMediapipe?.startSavingKeypoints(file, exerciseType)

            }
            currentState = RecordingState.RECORDING
            remainingTime = 20

            recordingJob = lifecycleScope.launch {
                while (remainingTime > 0 && isActive) {

                    val currentPose: Any?
                    val isPoseValid: Boolean

                    // Check if the user is still visible during recording
                    when (model) {
                        DetectionModel.MLKIT -> {
                            currentPose = poseDetectorMLKit?.getCurrentPose()
                            isPoseValid =
                                currentPose is Pose && poseDetectorMLKit!!.isPoseVisible(currentPose)
                        }

                        DetectionModel.MEDIAPIPE -> {
                            currentPose = poseDetectorMediapipe?.getCurrentPose()
                            isPoseValid =
                                currentPose is NormalizedLandmarkList && poseDetectorMediapipe!!.isPoseVisible(
                                    currentPose
                                )
                        }
                    }

                    if (!isPoseValid) {
                        runOnUiThread {
                            viewBinding.textView?.text = "Lost sight of user! Stopping recording..."
                            viewBinding.textView?.setTextColor(getColor(R.color.holo_red_dark))
                        }
                        delay(2000) // Small delay before completing
                        completeRecording(file) // Stop recording due to loss of visibility
                        return@launch
                    }
                    // Update UI with remaining time
                    viewBinding.textView?.text = "$remainingTime s remaining"
                    updateButtonUI("Stop ($remainingTime s)")
                    viewBinding.textView?.setTextColor(getColor(R.color.holo_blue_bright))
                    delay(1000)
                    remainingTime--
                }

                // Auto-complete when time runs out
                if (remainingTime <= 0 && isActive) {
                    completeRecording(file)
                }
            }

        } ?: run {
            Log.e(TAG, "Failed to create CSV file")
            Toast.makeText(this, "Error: Could not create data file", Toast.LENGTH_SHORT).show()
            resetToIdle()
        }
    }

    /**
     * Stops the recording process, saves the summary statistics to the database, and prepares for results.
     * Input: File? (optional CSV file, defaults to the class property)
     * Output: Unit (Stops coroutine and updates state)
     */
    private fun completeRecording(csvFile: File? = null) {
        recordingJob?.cancel() // Cancel the recording timer
        // Tell the detector to stop writing to the CSV file
        when (model) {
            DetectionModel.MLKIT -> poseDetectorMLKit?.stopSavingKeypoints()
            DetectionModel.MEDIAPIPE -> poseDetectorMediapipe?.stopSavingKeypoints()
        }

        currentState = RecordingState.READY_FOR_RESULTS
        updateButtonUI("Generate Results")

        runOnUiThread {
            viewBinding.textView?.text = "Recording complete!"
            viewBinding.textView?.setTextColor(getColor(android.R.color.holo_green_dark))
        }

        // Parse the recorded CSV data to calculate summary statistics
        val analysisResult = csvFile?.let { ReportGenerator.parseCSVData(it, exerciseType) }

        // Extract and calculate summary statistics for database entry
        val avgAsymmetry = if (analysisResult is AnalysisResult.AsymmetryResult) {
            analysisResult.stats.values.map { it.meanDiff }.average().toFloat() // Calculate overall average asymmetry
        } else null

        val maxAsymmetry = if (analysisResult is AnalysisResult.AsymmetryResult) {
            analysisResult.stats.values.maxOfOrNull { it.meanDiff }
        } else null

        val minAngle = if (analysisResult is AnalysisResult.AngleResult) {
            analysisResult.stats.values.minOfOrNull { it.meanAngle }
        } else null

        val maxAngle = if (analysisResult is AnalysisResult.AngleResult) {
            analysisResult.stats.values.maxOfOrNull { it.meanAngle }
        } else null

        // Create the database entity
        val resultEntity = csvFile?.let {
            ExerciseResultEntity(
                exerciseType = exerciseType,
                csvPath = it.absolutePath, // Save the path to the raw data
                avgAsymmetry = avgAsymmetry,
                maxAsymmetry = maxAsymmetry,
                avgAngle = minAngle,
                maxAngle = maxAngle // NOTE: Using maxAngle for minAngle here seems intentional based on the previous context but is potentially confusing
            )
        }

        // Insert the result into the Room database asynchronously
        lifecycleScope.launch {
            if (resultEntity != null) {
                db.exerciseResultDao().insertResult(resultEntity)
            }
        }
    }

    /**
     * Triggers the full analysis of the recorded CSV data and navigates to the ReportGenerator Activity.
     * Output: Unit (Starts analysis and navigation)
     */
    private fun generateAndShowResults() {
        csvFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "Analyzing results from: ${file.absolutePath}")
                updateButtonUI("Analyzing...")

                // Start analysis in background
                lifecycleScope.launch {
                    try {
                        // Perform the detailed analysis (may take time)
                        val reportPath = when (model) {
                            DetectionModel.MLKIT -> poseDetectorMLKit?.analyzeRecordedData(file)
                            DetectionModel.MEDIAPIPE -> poseDetectorMediapipe?.analyzeRecordedData(file)
                        } ?: throw Exception("Analysis failed")

                        // Navigate to results Activity, passing the path to the analyzed report
                        val intent = Intent(this@MainActivity, ReportGenerator::class.java).apply {
                            putExtra("REPORT_PATH", reportPath)
                            putExtra("EXERCISE_TYPE", exerciseType)
                        }
                        startActivity(intent)

                        resetToIdle() // Reset state after navigation
                    } catch (e: Exception) {
                        Log.e(TAG, "Error analyzing data", e)
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "Error analyzing data: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            resetToIdle()
                        }
                    }
                }
            } else {
                Log.w(TAG, "CSV file doesn't exist or is empty")
                Toast.makeText(this, "No data recorded", Toast.LENGTH_SHORT).show()
                resetToIdle()
            }
        } ?: run {
            Log.e(TAG, "No CSV file to analyze")
            Toast.makeText(this, "Error: No data file", Toast.LENGTH_SHORT).show()
            resetToIdle()
        }
    }

    /**
     * Resets the application state variables and UI back to the initial IDLE state.
     * Output: Unit (State reset)
     */
    private fun resetToIdle() {
        currentState = RecordingState.IDLE
        remainingTime = 20
        csvFile = null
        countdownJob?.cancel()
        recordingJob?.cancel()
        updateButtonUI("Start Exercise")
        viewBinding.textView?.text = "" // Clear status text
    }

    /**
     * Updates the text and background color of the start/stop button based on the current [currentState].
     * Input: String (text for the button)
     * Output: Unit (UI update)
     */
    private fun updateButtonUI(text: String) {
        runOnUiThread {
            viewBinding.btnStartAnalysis?.text = text

            // Set background color based on state
            val colorValue: Int = when (currentState) {
                RecordingState.IDLE -> Color.parseColor("#99B7F5") // Light blue/grey
                RecordingState.COUNTDOWN -> ContextCompat.getColor(this, R.color.holo_orange_dark) // Orange
                RecordingState.RECORDING -> ContextCompat.getColor(this, R.color.holo_blue_dark) // Dark blue
                RecordingState.READY_FOR_RESULTS -> ContextCompat.getColor(this, R.color.holo_green_light) // Light green
            }

            viewBinding.btnStartAnalysis?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(colorValue)
        }
    }

    /**
     * Sets up the camera provider, binds the camera to the lifecycle, and attaches the preview and image analyzer.
     * Output: Unit (Camera and analysis setup)
     */
    private fun startCameraWithAnalysis() {
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider?.unbindAll() // Unbind all use cases before rebinding

                // Preview setup
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = viewBinding.previewView?.surfaceProvider // Connect preview to ViewFinder
                    }

                // Image Analysis setup for pose detection
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // Use the latest frame only
                    .build()
                    .apply {
                        setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(imageProxy) // Custom frame processing logic
                        }
                    }

                // Use the front camera for user analysis
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                // Bind use cases to the camera and current lifecycle owner
                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer,
                )

                Log.d(TAG, "Camera started with analysis")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT)
                    .show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Processes each camera frame ([ImageProxy]) using the selected pose detection model.
     * It handles converting the frame into a format usable by the detector and updating the overlay.
     * Input: ImageProxy (the camera frame)
     * Output: Unit (Frame processing and overlay update)
     */
    @androidx.camera.core.ExperimentalGetImage
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            // Convert MediaImage to InputImage for ML Kit/MediaPipe
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            // ML Kit Pose Detection
            when (model) {
                DetectionModel.MEDIAPIPE -> {
                    poseDetectorMediapipe?.detectPose(imageProxy) // Detect pose with MediaPipe

                    poseDetectorMediapipe?.currentPoseLandmarks?.let { landmarkListProto ->
                        val list = landmarkListProto.landmarkList

                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        val isRotated = rotationDegrees == 90 || rotationDegrees == 270

                        val rotatedWidth = if (isRotated) image.height else image.width
                        val rotatedHeight = if (isRotated) image.width else image.height

                        // Update the custom view overlay with the detected landmarks
                        runOnUiThread {
                            viewBinding.poseOverlay?.updatePoseMediapipe(
                                list,
                                isFrontAnalysis,
                                rotatedWidth,
                                rotatedHeight
                            )
                        }
                    }

                    imageProxy.close() // Must close the ImageProxy when done with the frame
                }

                DetectionModel.MLKIT -> {
                    poseDetectorMLKit?.detectPose( // Detect pose with ML Kit
                        image,
                        exerciseType,
                        onSuccess = { pose ->
                            // Calculate rotated dimensions for correct overlay scaling
                            runOnUiThread {
                                val rotatedWidth = if (imageProxy.imageInfo.rotationDegrees == 90 ||
                                    imageProxy.imageInfo.rotationDegrees == 270) {
                                    image.height
                                } else {
                                    image.width
                                }

                                val rotatedHeight = if (imageProxy.imageInfo.rotationDegrees == 90 ||
                                    imageProxy.imageInfo.rotationDegrees == 270) {
                                    image.width
                                } else {
                                    image.height
                                }

                                viewBinding.poseOverlay?.updatePose( // Update overlay
                                    pose,
                                    isFrontAnalysis,
                                    rotatedWidth,
                                    rotatedHeight
                                )
                            }
                            imageProxy.close()
                        },
                        onFailure = { e ->
                            Log.e(TAG, "Pose detection failed: ${e.message}")
                            imageProxy.close()
                        }
                    )
                }
            }

        } else {
            imageProxy.close()
        }
    }


    private val activityResultLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions())
        { permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(baseContext,
                    "Permission request denied",
                    Toast.LENGTH_SHORT).show()
            } else {
                startCameraWithAnalysis() // Start camera if permissions are granted
                setupAnalysisButton() // Ensure button is set up after permissions
                resetToIdle()
            }
        }

    /**
     * Launches the activity result launcher to request necessary runtime permissions.
     * Output: Unit (Permission request dialog)
     */
    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    /**
     * Checks if all necessary camera and storage permissions have been granted.
     * Output: Boolean (True if all permissions are granted)
     */
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val TAG = "AsymmetryApp" // Tag for logging
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA, // Required for accessing the camera feed
                Manifest.permission.RECORD_AUDIO // Although not explicitly used for this analysis, often included with camera setups
            ).apply {
                // WRITE_EXTERNAL_STORAGE is required on older Android versions
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    /**
     * Called when the activity is being destroyed. Releases resources.
     * Output: Unit (Resource cleanup)
     */
    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown() // Shut down the camera thread pool
        // Close the pose detectors to release their resources
        when (model) {
            DetectionModel.MLKIT -> poseDetectorMLKit?.close()
            DetectionModel.MEDIAPIPE -> poseDetectorMediapipe?.close()
        }
        countdownJob?.cancel() // Cancel any active coroutines
        recordingJob?.cancel()
    }
}