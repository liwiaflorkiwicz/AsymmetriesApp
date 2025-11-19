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

class HistoryActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var llHistoryContainer: LinearLayout
    private lateinit var cvEmptyState: CardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.history_layout)

        llHistoryContainer = findViewById(R.id.llHistoryContainer)
        cvEmptyState = findViewById(R.id.cvEmptyState)

        // Initialize DB
        db = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "asymmetry_db"
        ).build()

        loadHistory()

        findViewById<View>(R.id.btnMenu).setOnClickListener {
            val intent = Intent(this, MenuActivity::class.java)
            startActivity(intent)
        }
    }

    private fun loadHistory() {
        lifecycleScope.launch(Dispatchers.IO) {
            val results = db.exerciseResultDao().getAllResults()

            withContext(Dispatchers.Main) {
                llHistoryContainer.removeAllViews()

                if (results.isEmpty()) {
                    cvEmptyState.visibility = View.VISIBLE
                } else {
                    cvEmptyState.visibility = View.GONE

                    results.forEach { result ->
                        val itemView = layoutInflater.inflate(R.layout.history_card_layout, llHistoryContainer, false)

                        // Populate views
                        itemView.findViewById<TextView>(R.id.tvSessionExercise).text = result.exerciseType
                        itemView.findViewById<TextView>(R.id.tvSessionDate).text =
                            SimpleDateFormat("MMM dd, yyyy - HH:mm", Locale.getDefault())
                                .format(Date(result.timestamp))

                        val (statText, qualityText, qualityColor) = buildStatAndQuality(result)
                        itemView.findViewById<TextView>(R.id.tvSessionStat).text = statText

                        val tvQuality = itemView.findViewById<TextView>(R.id.tvSessionQuality)
                        tvQuality.text = qualityText
                        tvQuality.setTextColor(qualityColor)

                        // Optional: click to open CSV or detailed report
                        itemView.setOnClickListener {
                            val intent = Intent(this@HistoryActivity, ReportGenerator::class.java)
                            intent.putExtra("REPORT_PATH", result.csvPath)
                            intent.putExtra("EXERCISE_TYPE", result.exerciseType)
                            intent.putExtra("TIMESTAMP", result.timestamp)
                            startActivity(intent)
                        }

                        llHistoryContainer.addView(itemView)
                    }
                }
            }
        }
    }

    private fun buildStatAndQuality(result: ExerciseResultEntity): Triple<String, String, Int> {
        // If we have asymmetry summary (in pixels), display that
        result.avgAsymmetry?.let { avg ->
            val max = result.maxAsymmetry ?: avg
            val statText = "Avg: ${"%.2f".format(avg)} % • Max: ${"%.2f".format(max)} %"

            val (qualityText, color) = when {
                max < 5.0f -> "Excellent" to Color.parseColor("#4CAF50") // green
                max < 10.0f -> "Good" to Color.parseColor("#FFC107")     // amber
                else -> "Needs Work" to Color.parseColor("#F44336")      // red
            }
            return Triple(statText, qualityText, color)
        }

        // Otherwise, if we have angle summary (degrees), display that
        result.avgAngle?.let { avgAngle ->
            val maxAngle = result.maxAngle ?: avgAngle
            val statText = "Avg: ${"%.1f".format(avgAngle)}° • Max: ${"%.1f".format(maxAngle)}°"

            // Choose ideal angle by exercise type (fallback general rules)
            val ideal = when (result.exerciseType) {
                "SQUAT", "SIDE_SQUAT" -> 90f
                "PLANK" -> 180f
                else -> 90f
            }
            val deviation = abs(avgAngle - ideal)

            val (qualityText, color) = when {
                deviation < 10f -> "Excellent" to Color.parseColor("#4CAF50")
                deviation < 20f -> "Good" to Color.parseColor("#FFC107")
                else -> "Needs Work" to Color.parseColor("#F44336")
            }
            return Triple(statText, qualityText, color)
        }

        // Fallback if no summary available
        return Triple("No summary", "Unknown", Color.parseColor("#999999"))
    }
}
