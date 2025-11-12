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

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var viewBinding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var imageAnalyzer: ImageAnalysis
    private lateinit var poseDetectorMLKit: PoseDetectorMLKit     // ML Kit Pose Detector wrapper
    private lateinit var db: AppDatabase

    private var exerciseType: String = "POSE" // Default
    private var isFrontAnalysis: Boolean = true // POSE, SQUAT, HAND_RISE are front

    private enum class RecordingState {
        IDLE,           // Initial state - button shows "Start Exercise"
        COUNTDOWN,      // 10 second countdown checking pose
        RECORDING,      // 20 second recording
        READY_FOR_RESULTS  // Recording complete, ready to generate results
    }

    private var currentState = RecordingState.IDLE
    private var countdownJob: Job? = null
    private var recordingJob: Job? = null
    private var remainingTime = 20 // seconds
    private var csvFile: File? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        Log.d(TAG, "onCreate: START")
        super.onCreate(savedInstanceState)

        try {
            // Get exercise type from intent
            exerciseType = intent.getStringExtra("extra_exercise_type") ?: "POSE"
            isFrontAnalysis = exerciseType in listOf("POSE", "SQUAT", "HAND_RISE")

            viewBinding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(viewBinding.root)
            viewBinding.textView?.bringToFront()
            viewBinding.btnStartAnalysis?.bringToFront()

            cameraExecutor = Executors.newSingleThreadExecutor()
            // ML Kit Pose Detector
            poseDetectorMLKit = PoseDetectorMLKit()

            // Request camera permissions
            if (allPermissionsGranted()) {
                startCameraWithAnalysis()
                setupAnalysisButton()
                resetToIdle()
            } else {
                requestPermissions()
            }

            // Build database
            db = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java,
                "asymmetry_db"
            ).build()

            viewBinding.btnMenu?.setOnClickListener {
                val intent = Intent(this, MenuActivity::class.java)
                startActivity(intent)
            }

            viewBinding.floatingActionButton?.setOnClickListener {
                val intent = Intent(this, HelpActivity::class.java)
                startActivity(intent)
            }

            Log.d(TAG, "onCreate: COMPLETED SUCCESSFULLY")

        } catch (e: Exception) {
            Log.e(TAG, "onCreate: FATAL ERROR", e)
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupAnalysisButton() {
        viewBinding.btnStartAnalysis?.setOnClickListener {
            Log.d(TAG, "Button pressed in state: $currentState")

            when (currentState) {
                RecordingState.IDLE -> {
                    startCountdown()
                }
                RecordingState.COUNTDOWN -> {
                    cancelCountdown()   // allowing cancel during countdown
                }
                RecordingState.RECORDING -> {
                    completeRecording() // stop recording
                }
                RecordingState.READY_FOR_RESULTS -> {
                    generateAndShowResults()
                }
            }
        }
    }

    private fun startCountdown() {
        currentState = RecordingState.COUNTDOWN
        updateButtonUI("Cancel")

        var isPoseVisibleInLastCheck = false
        var poseForScale: Pose? = null

        countdownJob = lifecycleScope.launch {
            for (i in 10 downTo 1) {
                if (!isActive) break

                val currentPose = poseDetectorMLKit.getCurrentPose()     // get latest pose PoseDetector
                val isPoseValid = currentPose != null && poseDetectorMLKit.isPoseVisible(currentPose)

                isPoseVisibleInLastCheck = isPoseValid

                runOnUiThread {
                    if (isPoseValid) {
                        poseForScale = currentPose
                        viewBinding.textView?.text = "Body detected - $i"
                        viewBinding.textView?.setTextColor(getColor(R.color.holo_green_dark))
                    } else {
                        viewBinding.textView?.text = "Position yourself - $i\nShow full body"
                        viewBinding.textView?.setTextColor(getColor(R.color.holo_orange_dark))
                    }
                }
                delay(1000)
            }

            // After countdown
            // Clear status message
            runOnUiThread {
                viewBinding.textView?.text = ""
            }
            if (isActive) {
                if (isPoseVisibleInLastCheck) {
                    // If pose was correct at the end of countdown
                    if (poseForScale != null && isFrontAnalysis) {
                        poseDetectorMLKit.calculateScale(poseForScale!!)
                        Log.d(TAG, "Normalization Scale Set before recording.")
                    }
                    startRecording()
                } else {
                    // If pose was never correct
                    runOnUiThread {
                        viewBinding.textView?.text = "Couldn't detect pose during countdown. Try again."
                        viewBinding.textView?.setTextColor(getColor(R.color.holo_red_dark))
                    }
                    resetToIdle()
                }
            }
        }
    }

    // when user not visible
    private fun cancelCountdown() {
        countdownJob?.cancel()
        resetToIdle()
    }

    private fun startRecording() {
        // Prepare files
        csvFile = File(filesDir, "keypoints_${System.currentTimeMillis()}_${exerciseType}.csv")

        // Start saving CSV keypoints
        csvFile?.let { file ->
            poseDetectorMLKit.startSavingKeypoints(file, exerciseType)
            currentState = RecordingState.RECORDING
            remainingTime = 20

            recordingJob = lifecycleScope.launch {
                while (remainingTime > 0 && isActive) {

                    val currentPose = poseDetectorMLKit.getCurrentPose()
                    val isPoseValid = currentPose != null && poseDetectorMLKit.isPoseVisible(currentPose)

                    if (!isPoseValid) {
                        runOnUiThread {
                            viewBinding.textView?.text = "Lost sight of user! Stopping recording..."
                            viewBinding.textView?.setTextColor(getColor(R.color.holo_red_dark))
                        }
                        delay(2000)
                        completeRecording(file)
                        return@launch
                    }
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

    private fun completeRecording(csvFile: File? = null) {
        recordingJob?.cancel()
        poseDetectorMLKit.stopSavingKeypoints()

        currentState = RecordingState.READY_FOR_RESULTS
        updateButtonUI("Generate Results")

        runOnUiThread {
            viewBinding.textView?.text = "Recording complete!"
            viewBinding.textView?.setTextColor(getColor(android.R.color.holo_green_dark))
        }

        // save to database (also include video path)
        val analysisResult = csvFile?.let { ReportGenerator.parseCSVData(it, exerciseType) }

        val avgAsymmetry = if (analysisResult is AnalysisResult.AsymmetryResult) {
            analysisResult.stats.values.map { it.meanDiff }.average().toFloat()
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

        val resultEntity = csvFile?.let {
            ExerciseResultEntity(
                exerciseType = exerciseType,
                csvPath = it.absolutePath,
                avgAsymmetry = avgAsymmetry,
                maxAsymmetry = maxAsymmetry,
                avgAngle = minAngle,
                maxAngle = maxAngle
            )
        }

        lifecycleScope.launch {
            if (resultEntity != null) {
                db.exerciseResultDao().insertResult(resultEntity)
            }
        }
    }

    private fun generateAndShowResults() {
        csvFile?.let { file ->
            if (file.exists() && file.length() > 0) {
                Log.d(TAG, "Analyzing results from: ${file.absolutePath}")
                updateButtonUI("Analyzing...")

                // Start analysis in background
                lifecycleScope.launch {
                    try {
                        // Analyze the data
                        val reportPath = poseDetectorMLKit.analyzeRecordedData(file)

                        // Navigate to results
                        val intent = Intent(this@MainActivity, ReportGenerator::class.java).apply {
                            putExtra("REPORT_PATH", reportPath)
                            putExtra("EXERCISE_TYPE", exerciseType)
                        }
                        startActivity(intent)

                        resetToIdle()
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

    private fun resetToIdle() {
        currentState = RecordingState.IDLE
        remainingTime = 20
        csvFile = null
        countdownJob?.cancel()
        recordingJob?.cancel()
        updateButtonUI("Start Exercise")
        viewBinding.textView?.text = ""
    }

    private fun updateButtonUI(text: String) {
        runOnUiThread {
            viewBinding.btnStartAnalysis?.text = text

            // Set background color based on state
            val colorValue: Int = when (currentState) {
                RecordingState.IDLE -> Color.parseColor("#99B7F5")
                RecordingState.COUNTDOWN -> ContextCompat.getColor(this, R.color.holo_orange_dark)
                RecordingState.RECORDING -> ContextCompat.getColor(this, R.color.holo_blue_dark)
                RecordingState.READY_FOR_RESULTS -> ContextCompat.getColor(this, R.color.holo_green_light)
            }

            viewBinding.btnStartAnalysis?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(colorValue)
        }
    }

    private fun startCameraWithAnalysis() {
        val cameraProviderFuture: ListenableFuture<ProcessCameraProvider> =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider?.unbindAll()

                // Preview
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = viewBinding.previewView?.surfaceProvider
                    }

                // Image Analysis for pose detection and overlay
                imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .apply {
                        setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(imageProxy)
                        }
                    }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer,
                )

                Log.d(TAG, "Camera started with analysis")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(
                mediaImage,
                imageProxy.imageInfo.rotationDegrees
            )

            // ML Kit Pose Detection
            poseDetectorMLKit.detectPose(
                image,
                exerciseType,
                onSuccess = { pose ->
                    // Update overlay with pose
                    runOnUiThread {
                        val rotatedWidth = if (imageProxy.imageInfo.rotationDegrees == 90 ||
                            imageProxy.imageInfo.rotationDegrees == 270) {
                            image.height  // 480
                        } else {
                            image.width
                        }

                        val rotatedHeight = if (imageProxy.imageInfo.rotationDegrees == 90 ||
                            imageProxy.imageInfo.rotationDegrees == 270) {
                            image.width  // 640
                        } else {
                            image.height
                        }

                        viewBinding.poseOverlay?.updatePose(
                            pose,
                            isFrontAnalysis,
                            rotatedWidth,   // Now 480
                            rotatedHeight   // Now 640
                        )
                    }
                    imageProxy.close()
                },
                onFailure = { e ->
                    Log.e(TAG, "Pose detection failed: ${e.message}")
                    imageProxy.close()
                }
            )
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
                startCameraWithAnalysis()
            }
        }

    private fun requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        const val TAG = "AsymmetryApp"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseDetectorMLKit.close()
        countdownJob?.cancel()
        recordingJob?.cancel()
    }
}