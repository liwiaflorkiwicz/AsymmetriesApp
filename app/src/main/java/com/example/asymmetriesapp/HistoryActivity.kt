package com.example.asymmetriesapp

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Activity responsible for displaying a list of past exercise results/sessions.
 * It retrieves data from the Room database and presents it in a scrollable history view.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase // Holds the reference to the Room database
    private lateinit var llHistoryContainer: LinearLayout // The container where session cards are added
    private lateinit var cvEmptyState: CardView // Card to display when history is empty

    /**
     * Initializes the activity, sets up views, initializes the database, and loads the history data.
     * Input: Bundle? (savedInstanceState)
     * Output: Unit (Activity initialization and data loading)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.history_layout)

        // Initialize view references
        llHistoryContainer = findViewById(R.id.llHistoryContainer)
        cvEmptyState = findViewById(R.id.cvEmptyState)

        // Initialize DB
        // Creates the database instance using Room's databaseBuilder
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "asymmetry_db" // The name of the database file
        ).build()

        loadHistory() // Start the process of fetching and displaying results

        // Set up the Menu button click listener
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            val intent = Intent(this, MenuActivity::class.java)
            startActivity(intent)
        }
    }

    /**
     * Fetches all exercise results from the database on a background thread and updates the UI on the main thread.
     * Output: Unit (updates the history view)
     */
    private fun loadHistory() {
        // Launches a coroutine tied to the Activity's lifecycle
        lifecycleScope.launch(Dispatchers.IO) { // Switch to I/O thread for database operation
            val results = db.exerciseResultDao().getAllResults() // Fetch all results

            withContext(Dispatchers.Main) { // Switch back to the Main thread to update the UI
                llHistoryContainer.removeAllViews() // Clear any existing views

                if (results.isEmpty()) {
                    cvEmptyState.visibility = View.VISIBLE // Show "No history" card
                } else {
                    cvEmptyState.visibility = View.GONE // Hide "No history" card

                    results.forEach { result ->
                        // Inflate the layout for a single history item (card)
                        val itemView = layoutInflater.inflate(R.layout.history_card_layout, llHistoryContainer, false)

                        // Populate views
                        itemView.findViewById<TextView>(R.id.tvSessionExercise).text = result.exerciseType
                        // Format the timestamp into a readable date string
                        itemView.findViewById<TextView>(R.id.tvSessionDate).text =
                            SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault())
                                .format(Date(result.timestamp))

                        // Calculate and get the summary text, quality text, and color
                        val (statText, qualityText, qualityColor) = buildStatAndQuality(result)
                        itemView.findViewById<TextView>(R.id.tvSessionStat).text = statText

                        val tvQuality = itemView.findViewById<TextView>(R.id.tvSessionQuality)
                        tvQuality.text = qualityText
                        tvQuality.setTextColor(qualityColor) // Apply the quality color

                        // Set up click listener to open a detailed report
                        itemView.setOnClickListener {
                            val intent = Intent(this@HistoryActivity, ReportGenerator::class.java)
                            intent.putExtra("REPORT_PATH", result.csvPath) // Pass data to the ReportGenerator
                            intent.putExtra("EXERCISE_TYPE", result.exerciseType)
                            intent.putExtra("TIMESTAMP", result.timestamp)
                            startActivity(intent)
                        }

                        llHistoryContainer.addView(itemView) // Add the populated card to the container
                    }
                }
            }
        }
    }

    /**
     * Determines the display string, quality text, and color based on the available summary data (asymmetry or angle).
     * Input: ExerciseResultEntity (the session result data)
     * Output: Triple<String, String, Int> (stat summary text, quality text, quality color)
     */
    private fun buildStatAndQuality(result: ExerciseResultEntity): Triple<String, String, Int> {
        // If we have asymmetry summary (in percentage), display that
        result.avgAsymmetry?.let { avg ->
            val max = result.maxAsymmetry ?: avg
            val statText = "Avg: ${"%.2f".format(avg)} % • Max: ${"%.2f".format(max)} %"

            // Assign quality based on average asymmetry percentage
            val (qualityText, color) = when {
                avg < 2.0f -> "Excellent" to Color.parseColor("#4CAF50") // Green for low asymmetry
                avg < 5.0f -> "Good" to Color.parseColor("#FFC107")     // Amber for moderate asymmetry
                else -> "Needs Work" to Color.parseColor("#F44336")      // Red for high asymmetry
            }
            return Triple(statText, qualityText, color)
        }

        // Otherwise, if we have angle summary (degrees), display that
        result.maxAngle?.let { maxAngle ->
            val avgAngle = result.avgAngle ?: maxAngle
            val statText = "Avg: ${"%.1f".format(avgAngle)}° • Max: ${"%.1f".format(maxAngle)}°"

            // Choose ideal angle by exercise type
            val ideal = when (result.exerciseType) {
                "SQUAT", "SIDE_SQUAT" -> 90f // Ideal depth angle for squats
                "PLANK" -> 180f // Ideal straight angle for plank
                else -> 90f // Default ideal angle
            }
            val deviation = abs(avgAngle - ideal) // Calculate the absolute difference from the ideal angle

            // Assign quality based on deviation from the ideal angle
            val (qualityText, color) = when {
                deviation < 10f -> "Excellent" to Color.parseColor("#4CAF50")
                deviation < 20f -> "Good" to Color.parseColor("#FFC107")
                else -> "Needs Work" to Color.parseColor("#F44336")
            }
            return Triple(statText, qualityText, color)
        }

        // Fallback if no summary data is available
        return Triple("No summary", "Unknown", Color.parseColor("#999999"))
    }
}