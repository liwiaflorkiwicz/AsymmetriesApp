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

class ReportGenerator : AppCompatActivity() {
    private lateinit var binding: ReportLayoutBinding
    private var exerciseType: String = "POSE"
    private var reportPath: String = ""
    private var videoPath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ReportLayoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get data from intent
        reportPath = intent.getStringExtra("REPORT_PATH") ?: ""
        videoPath = intent.getStringExtra("VIDEO_PATH") ?: ""
        exerciseType = intent.getStringExtra("EXERCISE_TYPE") ?: "POSE"

        if (reportPath.isEmpty()) {
            Toast.makeText(this, "Error: No report data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Setup UI
        setupHeader()

        binding.btnHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            finish()
        }

        // Load and analyze data
        loadAndDisplayResults()
    }

    private fun setupHeader() {
        binding.tvReportTitle.text = "Analysis Report"
        binding.tvExerciseType.text = "Exercise: ${getExerciseName(exerciseType)}"

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        binding.tvTimestamp.text = dateFormat.format(Date())
    }

    private fun loadAndDisplayResults() {
        lifecycleScope.launch {
            try {
                val file = File(reportPath)
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
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

    private fun displayResults(result: AnalysisResult) {
        when (result) {
            is AnalysisResult.AsymmetryResult -> displayAsymmetryResults(result)
            is AnalysisResult.AngleResult -> displayAngleResults(result)
        }
    }

    private fun displayAsymmetryResults(result: AnalysisResult.AsymmetryResult) {
        // Generate overall feedback
        val maxAsymmetry = result.stats.values.maxByOrNull { it.meanDiff }
        val feedback = generateAsymmetryFeedback(result.stats, maxAsymmetry)
        binding.tvOutputFeedback.text = feedback

        // Clear previous results
        binding.llResultsContainer.removeAllViews()

        // Add result card for each body part
        result.stats.entries.sortedByDescending { it.value.meanDiff }.forEach { (bodyPart, stats) ->
            val cardView = createAsymmetryCard(bodyPart, stats)
            binding.llResultsContainer.addView(cardView)
        }
    }

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

    private fun createAsymmetryCard(bodyPart: String, stats: AsymmetryStats): CardView {
        val cardView = LayoutInflater.from(this).inflate(
            R.layout.result_card_layout,
            binding.llResultsContainer,
            false
        ) as CardView

        cardView.findViewById<TextView>(R.id.tvBodyPart).text =
            bodyPart.replaceFirstChar { it.uppercase() }

        cardView.findViewById<TextView>(R.id.tvMeanValue).text =
            "Average: %.2f px".format(stats.meanDiff)

        cardView.findViewById<TextView>(R.id.tvMaxValue).text =
            "Max: %.2f px".format(stats.maxDiff)

        cardView.findViewById<TextView>(R.id.tvMinValue).text =
            "Min: %.2f px".format(stats.minDiff)

        cardView.findViewById<TextView>(R.id.tvStdDev).text =
            "Std Dev: %.2f px".format(stats.stdDev)

        // Color code based on severity
        val severityColor = when {
            stats.meanDiff < 8 -> "#4CAF50" // Green - Good
            stats.meanDiff < 16 -> "#FFC107" // Yellow - Moderate
            else -> "#F44336" // Red - High
        }
        cardView.findViewById<TextView>(R.id.tvBodyPart).setTextColor(
            android.graphics.Color.parseColor(severityColor)
        )

        return cardView
    }

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

        if (angleType == "squat_angle") {
            cardView.findViewById<LinearLayout>(R.id.meanRow).visibility = View.GONE
            cardView.findViewById<LinearLayout>(R.id.stdRow).visibility = View.GONE
        }

        val severityColor = if (angleType == "plank_angle") {
            when {
                stats.meanAngle >= 170 -> "#4CAF50" // Excellent (Green)
                stats.meanAngle >= 160 -> "#FFC107" // Good (Yellow)
                else -> "#F44336" // Needs Improvement (Red)
            }
        } else {
            when {
                stats.minAngle < 60 || stats.maxAngle > 190 -> "#4CAF50" // Very good (Green)
                stats.minAngle <= 90 || stats.maxAngle >= 160 -> "#FFC107" // Good (Yellow)
                else -> "#F44336" // Bad (Red)
            }
        }
        cardView.findViewById<TextView>(R.id.tvBodyPart).setTextColor(
            android.graphics.Color.parseColor(severityColor)
        )

        return cardView
    }

    private fun generateAsymmetryFeedback(
        stats: Map<String, AsymmetryStats>,
        maxAsymmetry: AsymmetryStats?
    ): String {

        // INSERT OUTPUT FROM MODEL - ASYMMETRY_SCORE + CORRECTNESS_SCORE + RECOMMENDATIONS

        if (maxAsymmetry == null || stats.isEmpty()) {
            return "No significant asymmetries detected. Good job!"
        }

        val avgAsymmetry = stats.values.map { it.meanDiff }.average()

        return buildString {
            when {
                avgAsymmetry < 5 -> {
                    append("Excellent! Your body shows good overall symmetry with minimal imbalances. ")
                }
                avgAsymmetry < 15 -> {
                    append("Good work! Some minor asymmetries detected, which is normal. ")
                }
                else -> {
                    append("Noticeable asymmetries detected. Consider focusing on balanced exercises. ")
                }
            }

            append("\n\nHighest asymmetry: ${maxAsymmetry.bodyPart} ")
            append("(${String.format("%.1f", maxAsymmetry.meanDiff)} px average difference)")
        }
    }

    private fun generateAngleFeedback(stats: Map<String, AngleStats>): String {
        if (stats.isEmpty()) {
            return "No angle data available."
        }

        // INSERT OUTPUT FROM MODEL - ASYMMETRY_SCORE + CORRECTNESS_SCORE + RECOMMENDATIONS

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
                            angleStats.minAngle < 90 || angleStats.maxAngle > 160 ->
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

        private fun calculateStdDev(values: List<Float>, mean: Float): Float {
            if (values.size < 2) return 0f
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

data class AsymmetryStats(
    val bodyPart: String,
    val meanDiff: Float,
    val maxDiff: Float,
    val minDiff: Float,
    val stdDev: Float,
    val sampleCount: Int
)

data class AngleStats(
    val angleType: String,
    val meanAngle: Float,
    val maxAngle: Float,
    val minAngle: Float,
    val stdDev: Float,
    val sampleCount: Int
)

