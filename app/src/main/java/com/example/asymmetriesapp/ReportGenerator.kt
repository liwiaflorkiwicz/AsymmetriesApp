package com.example.asymmetriesapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.example.asymmetriesapp.databinding.ReportLayoutBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Activity responsible for generating and displaying the analysis report
 * from a CSV file generated during an exercise session.
 */
class ReportGenerator : AppCompatActivity() {
    private lateinit var binding: ReportLayoutBinding
    private var exerciseType: String = "POSE"
    private var reportPath: String = ""
    private var videoPath: String = ""
    private var sessionTimestamp: Long = 0L

    /**
     * Initializes the activity, binds the layout, retrieves data from the intent,
     * sets up the header, and initiates loading the analysis results.
     * Input: Bundle? (savedInstanceState)
     * Output: Unit (Activity initialization)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ReportLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get report data path, video path, exercise type, and timestamp from the intent.
        reportPath = intent.getStringExtra("REPORT_PATH") ?: ""
        videoPath = intent.getStringExtra("VIDEO_PATH") ?: ""
        exerciseType = intent.getStringExtra("EXERCISE_TYPE") ?: "POSE"
        sessionTimestamp = intent.getLongExtra("TIMESTAMP", 0L)

        if (reportPath.isEmpty()) {
            Toast.makeText(this, "Error: No report data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Setup UI
        setupHeader()

        binding.btnHistory.setOnClickListener {
            // Navigate back to HistoryActivity, clearing the activity stack above it
            val intent = Intent(this, HistoryActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }

        // Load and analyze data
        loadAndDisplayResults()
    }

    /**
     * Sets the report title, exercise type name, and formatted timestamp in the header UI.
     * Output: Unit (Updates header TextViews)
     */
    private fun setupHeader() {
        binding.tvReportTitle.text = "Analysis Report"
        binding.tvExerciseType.text = "Exercise: ${getExerciseName(exerciseType)}"

        val dateFormat = SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault())
        binding.tvTimestamp.text = dateFormat.format(Date(sessionTimestamp))
    }

    /**
     * Initiates the asynchronous loading and parsing of the CSV report file.
     * It runs the heavy CSV parsing on a background thread (Dispatchers.IO).
     * Output: Unit (Kicks off a coroutine to load/display results)
     */
    private fun loadAndDisplayResults() {
        lifecycleScope.launch {
            try {
                val file = File(reportPath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        // End activity if report file doesn't exist
                        finish()
                    }
                    return@launch
                }

                // Parse CSV in background
                val analysisResult = withContext(Dispatchers.IO) {
                    parseCSVData(file, exerciseType)
                }

                // Display results on main thread
                withContext(Dispatchers.Main) {
                    displayResults(analysisResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading results", e)
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }

    /**
     * Directs the flow to the correct display function based on the analysis result type.
     * Input: AnalysisResult
     * Output: Unit (Calls displayAsymmetryResults or displayAngleResults)
     */
    private fun displayResults(result: AnalysisResult) {
        when (result) {
            is AnalysisResult.AsymmetryResult -> displayAsymmetryResults(result)
            is AnalysisResult.AngleResult -> displayAngleResults(result)
            else -> {
                Log.e(TAG, "Unknown analysis result type")
            }
        }
    }

    /**
     * Generates and displays feedback and individual result cards for Asymmetry analysis.
     * Results are displayed in the llResultsContainer.
     * Input: AnalysisResult.AsymmetryResult (map of body parts to AsymmetryStats)
     * Output: Unit (Populates UI with asymmetry data)
     */
    private fun displayAsymmetryResults(result: AnalysisResult.AsymmetryResult) {
        // Generate overall feedback
        val maxAsymmetry = result.stats.values.maxByOrNull { it.meanDiff }
        val feedback = generateAsymmetryFeedback(result.stats, maxAsymmetry)
        binding.tvOutputFeedback.text = feedback

        // Clear previous results
        binding.llResultsContainer.removeAllViews()

        // Add result card for each body part, sorted by descending mean difference (most severe first)
        result.stats.entries.sortedByDescending { it.value.meanDiff }.forEach { (bodyPart, stats) ->
            val cardView = createAsymmetryCard(bodyPart, stats)
            binding.llResultsContainer.addView(cardView)
        }
    }

    /**
     * Generates and displays feedback and individual result cards for Angle analysis.
     * Results are displayed in the llResultsContainer.
     * Input: AnalysisResult.AngleResult (map of angle types to AngleStats)
     * Output: Unit (Populates UI with angle data)
     */
    private fun displayAngleResults(result: AnalysisResult.AngleResult) {
        // Generate overall feedback
        val feedback = generateAngleFeedback(result.stats)
        binding.tvOutputFeedback.text = feedback

        // Clear previous results
        binding.llResultsContainer.removeAllViews()

        // Add result card for each angle type
        result.stats.forEach { (angleType, stats) ->
            val cardView = createAngleCard(angleType, stats)
            binding.llResultsContainer.addView(cardView)
        }
    }

    /**
     * Creates a CardView to display the statistics for a single body part's asymmetry.
     * Includes color coding based on the severity of the mean difference.
     * Input: String (bodyPart), AsymmetryStats (stats)
     * Output: CardView (The populated CardView for asymmetry results)
     */
    private fun createAsymmetryCard(bodyPart: String, stats: AsymmetryStats): CardView {
        val cardView = LayoutInflater.from(this).inflate(
            R.layout.result_card_layout,
            binding.llResultsContainer,
            false
        ) as CardView

        cardView.findViewById<TextView>(R.id.tvBodyPart).text =
            bodyPart.replaceFirstChar { it.uppercase() }

        // Populate TextViews with formatted statistics
        cardView.findViewById<TextView>(R.id.tvMeanValue).text =
            getString(R.string.avg_asym).format(stats.meanDiff)

        cardView.findViewById<TextView>(R.id.tvMaxValue).text =
            getString(R.string.max_asym).format(stats.maxDiff)

        cardView.findViewById<TextView>(R.id.tvMinValue).text =
            getString(R.string.min_asym).format(stats.minDiff)

        cardView.findViewById<TextView>(R.id.tvStdDev).text =
            getString(R.string.std_asym).format(stats.stdDev)

        // Color code based on severity of mean difference: Green (<2), Yellow (<5), Red (>=5)
        val severityColor = when {
            stats.meanDiff < 2 -> "#4CAF50" // Green - Good
            stats.meanDiff < 5 -> "#FFC107" // Yellow - Moderate
            else -> "#F44336" // Red - High
        }
        cardView.findViewById<TextView>(R.id.tvBodyPart).setTextColor(
            android.graphics.Color.parseColor(severityColor)
        )

        return cardView
    }

    /**
     * Creates a CardView to display the statistics for a single exercise angle.
     * Includes custom display names and specific logic for hiding mean/stdDev for squat angle.
     * Input: String (angleType), AngleStats (stats)
     * Output: CardView (The populated CardView for angle results)
     */
    private fun createAngleCard(angleType: String, stats: AngleStats): CardView {
        val cardView = LayoutInflater.from(this).inflate(
            R.layout.result_card_layout,
            binding.llResultsContainer,
            false
        ) as CardView

        val displayName = when (angleType) {
            "squat_angle" -> "Squat Angle (Knee)"
            "plank_angle" -> "Plank Angle (Hip)"
            else -> angleType.replace("_", " ").replaceFirstChar { it.uppercase() }
        }

        cardView.findViewById<TextView>(R.id.tvBodyPart).text = displayName

        cardView.findViewById<TextView>(R.id.tvMaxValue).text =
            "Max: %.1f°".format(stats.maxAngle)

        cardView.findViewById<TextView>(R.id.tvMinValue).text =
            "Min: %.1f°".format(stats.minAngle)

        // Squat analysis only needs min/max angle for range of motion
        if (angleType == "squat_angle") {
            cardView.findViewById<LinearLayout>(R.id.meanRow).visibility = View.GONE
            cardView.findViewById<LinearLayout>(R.id.stdRow).visibility = View.GONE
        }

        // Logic for color coding severity based on angle type
        val severityColor = if (angleType == "plank_angle") {
            when {
                stats.meanAngle >= 170 -> "#4CAF50" // Excellent (Green - straighter body)
                stats.meanAngle >= 160 -> "#FFC107" // Good (Yellow)
                else -> "#F44336" // Needs Improvement (Red)
            }
        } else {
            // angleType == "squat_angle" - criteria for good form is within a range (e.g., 60-190)
            when {
                stats.minAngle < 60 || stats.maxAngle > 190 -> "#4CAF50" // Excellent (Green)
                stats.minAngle <= 90 || stats.maxAngle >= 160 -> "#FFC107" // Good (Yellow)
                else -> "#F44336" // Needs Improvement (Red)
            }
        }
        cardView.findViewById<TextView>(R.id.tvBodyPart).setTextColor(
            android.graphics.Color.parseColor(severityColor)
        )

        return cardView
    }

    /**
     * Generates a summary text feedback for the overall asymmetry results.
     * Input: Map<String, AsymmetryStats> (stats), AsymmetryStats? (maxAsymmetry)
     * Output: String (Overall asymmetry feedback)
     */
    private fun generateAsymmetryFeedback(
        stats: Map<String, AsymmetryStats>,
        maxAsymmetry: AsymmetryStats?
    ): String {
        if (maxAsymmetry == null || stats.isEmpty()) {
            return "No asymmetries detected. App is not working!"
        }

        return buildString {
            val maxAsym = stats.values.maxByOrNull { it.meanDiff }?.maxDiff
            if (maxAsym != null) {
                when {
                    maxAsym < 2 -> {
                        append("Excellent! Your body shows good overall symmetry with minimal imbalances. ")
                    }

                    maxAsym < 5 -> {
                        append("Good work! Some minor asymmetries detected, which is normal. ")
                    }

                    else -> {
                        append("Noticeable asymmetries detected. Consider focusing on balanced exercises. ")
                    }
                }
            }

            append("\n\nHighest asymmetry: ${maxAsymmetry.bodyPart} ")
            append("(${String.format("%.1f", maxAsymmetry.meanDiff)} % average difference)")
        }
    }

    /**
     * Generates a summary text feedback for the overall angle results.
     * Input: Map<String, AngleStats> (stats)
     * Output: String (Overall angle feedback)
     */
    private fun generateAngleFeedback(stats: Map<String, AngleStats>): String {
        if (stats.isEmpty()) {
            return "No angle data available."
        }

        return buildString {
            stats.forEach { (angleType, angleStats) ->
                val displayName = when (angleType) {
                    "squat_angle" -> "Squat"
                    "plank_angle" -> "Plank"
                    else -> angleType
                }

                append("$displayName: ")

                when (angleType) {
                    "squat_angle" -> {
                        when {
                            angleStats.minAngle < 60 || angleStats.maxAngle > 190 ->
                                append("Very good! Excellent squat form.\n")
                            angleStats.minAngle <= 90 || angleStats.maxAngle >= 160 ->
                                append("Good form! Some room for improvement.\n")
                            else ->
                                append("Bad form detected. Try improving your squat depth and stability.\n")
                        }
                    }
                    "plank_angle" -> {
                        when {
                            angleStats.meanAngle >= 170 -> append("Excellent! Your body alignment is very straight.\n")
                            angleStats.meanAngle >= 160 -> append("Good form! Keep your core engaged.\n")
                            else -> append("Try to straighten your body more for better alignment.\n")
                        }
                    }
                }
            }
        }
    }

    /**
     * Converts the internal exercise type string to a more user-friendly name.
     */
    private fun getExerciseName(type: String): String {
        return when (type) {
            "POSE" -> "Standing Pose"
            "SQUAT" -> "Squat"
            "HAND_RISE" -> "Hand Rise"
            "SIDE_SQUAT" -> "Side Squat"
            "PLANK" -> "Plank"
            else -> type
        }
    }

    companion object {
        private const val TAG = "ReportGenerator"

        /**
         * Reads the CSV file and delegates to the appropriate analysis function
         * (asymmetry or angle) based on the exercise type.
         * Input: File (CSV file), String (exerciseType)
         * Output: AnalysisResult (Sealed class containing either AsymmetryResult or AngleResult)
         */
        fun parseCSVData(file: File, exerciseType: String): AnalysisResult {
            val lines = file.readLines()
            if (lines.size < 2) {
                throw IllegalArgumentException("CSV file is empty or invalid")
            }

            val header = lines[0].split(",")
            val dataLines = lines.drop(1) // Skip header

            val isFrontAnalysis = exerciseType in listOf("POSE", "SQUAT", "HAND_RISE")
            val isSideAnalysis = exerciseType in listOf("SIDE_SQUAT", "PLANK")

            return if (isFrontAnalysis) {
                analyzeAsymmetryData(header, dataLines)
            } else if (isSideAnalysis) {
                analyzeAngleData(header, dataLines, exerciseType)
            } else {
                throw IllegalArgumentException("Unsupported exercise type for analysis")
            }
        }

        /**
         * Processes CSV data to calculate asymmetry statistics for multiple body parts.
         * Asymmetry is measured by height difference columns (e.g., shoulder_height_diff).
         * Input: List<String> (header), List<String> (dataLines)
         * Output: AnalysisResult (AsymmetryResult with calculated stats)
         */
        private fun analyzeAsymmetryData(header: List<String>, dataLines: List<String>): AnalysisResult {
            val bodyParts = listOf("shoulder", "hip", "knee", "ankle", "elbow", "ear")
            val asymmetryStats = mutableMapOf<String, AsymmetryStats>()

            bodyParts.forEach { bodyPart ->
                val columnName = "${bodyPart}_height_diff"
                val columnIndex = header.indexOf(columnName)

                if (columnIndex != -1) {
                    val values = mutableListOf<Float>()

                    dataLines.forEach { line ->
                        val columns = line.split(",")
                        if (columnIndex < columns.size) {
                            val value = columns[columnIndex]
                            // Only add valid float values, skipping "NaN" or blank entries
                            if (value != "NaN" && value.isNotBlank()) {
                                values.add(value.toFloatOrNull() ?: 0f)
                            }
                        }
                    }

                    if (values.isNotEmpty()) {
                        val mean = values.average().toFloat()
                        val max = values.maxOrNull() ?: 0f
                        val min = values.minOrNull() ?: 0f
                        val stdDev = calculateStdDev(values, mean)

                        asymmetryStats[bodyPart] = AsymmetryStats(
                            bodyPart = bodyPart,
                            meanDiff = mean,
                            maxDiff = max,
                            minDiff = min,
                            stdDev = stdDev,
                            sampleCount = values.size
                        )
                    }
                }
            }
            return AnalysisResult.AsymmetryResult(asymmetryStats)
        }

        /**
         * Processes CSV data to calculate angle statistics for specific exercise angles.
         * Input: List<String> (header), List<String> (dataLines), String (exerciseType)
         * Output: AnalysisResult (AngleResult with calculated stats)
         */
        private fun analyzeAngleData(header: List<String>, dataLines: List<String>, exerciseType: String): AnalysisResult {
            val angleTypes = when (exerciseType) {
                "SIDE_SQUAT" -> listOf("squat_angle")
                "PLANK" -> listOf("plank_angle")
                else -> listOf("squat_angle", "plank_angle")
            }

            val angleStats = mutableMapOf<String, AngleStats>()

            angleTypes.forEach { angleType ->
                val columnIndex = header.indexOf(angleType)

                if (columnIndex != -1) {
                    val values = mutableListOf<Float>()

                    dataLines.forEach { line ->
                        val columns = line.split(",")
                        if (columnIndex < columns.size) {
                            val value = columns[columnIndex]
                            // Only add valid float values, skipping "NaN" or blank entries
                            if (value != "NaN" && value.isNotBlank()) {
                                values.add(value.toFloatOrNull() ?: 0f)
                            }
                        }
                    }

                    if (values.isNotEmpty()) {
                        val mean = values.average().toFloat()
                        val max = values.maxOrNull() ?: 0f
                        val min = values.minOrNull() ?: 0f
                        val stdDev = calculateStdDev(values, mean)

                        angleStats[angleType] = AngleStats(
                            angleType = angleType,
                            meanAngle = mean,
                            maxAngle = max,
                            minAngle = min,
                            stdDev = stdDev,
                            sampleCount = values.size
                        )
                    }
                }
            }

            return AnalysisResult.AngleResult(angleStats)
        }

        /**
         * Calculates the standard deviation of a list of float values.
         * Input: List<Float> (values), Float (mean)
         * Output: Float (Standard deviation)
         */
        private fun calculateStdDev(values: List<Float>, mean: Float): Float {
            if (values.size < 2) return 0f
            // Standard deviation formula: sqrt( sum((x_i - mean)^2) / N )
            val variance = values.map { (it - mean) * (it - mean) }.average()
            return kotlin.math.sqrt(variance).toFloat()
        }
    }
}

// Data classes for analysis results
sealed class AnalysisResult {
    data class AsymmetryResult(val stats: Map<String, AsymmetryStats>) : AnalysisResult()
    data class AngleResult(val stats: Map<String, AngleStats>) : AnalysisResult()
}

// Data class to hold calculated statistics for a body part's asymmetry
data class AsymmetryStats(
    val bodyPart: String,
    val meanDiff: Float,
    val maxDiff: Float,
    val minDiff: Float,
    val stdDev: Float,
    val sampleCount: Int
)

// Data class to hold calculated statistics for an exercise angle
data class AngleStats(
    val angleType: String,
    val meanAngle: Float,
    val maxAngle: Float,
    val minAngle: Float,
    val stdDev: Float,
    val sampleCount: Int
)