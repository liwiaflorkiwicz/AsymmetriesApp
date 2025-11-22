package com.example.asymmetriesapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.asymmetriesapp.databinding.ActivityMenuBinding // Import for view binding

/**
 * Activity serving as the main menu or exercise selection screen for the application.
 * It allows the user to choose an exercise type, view history, or access the help screen.
 */
class MenuActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMenuBinding // Instance of the view binding class

    /**
     * Initializes the activity and sets up click listeners for all menu buttons.
     * Input: Bundle? (savedInstanceState)
     * Output: Unit (Activity initialization and UI setup)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMenuBinding.inflate(layoutInflater) // Inflate the layout using view binding
        setContentView(binding.root) // Set the root view

        // Listener for the Pose Analysis button
        binding.btnPose.setOnClickListener {
            openCameraActivity("POSE") // Start MainActivity with POSE type
        }

        // Listener for the Squat button
        binding.btnSquat.setOnClickListener {
            openCameraActivity("SQUAT") // Start MainActivity with SQUAT type
        }

        // Listener for the Hand Rises button
        binding.btnRises.setOnClickListener {
            openCameraActivity("HAND_RISE") // Start MainActivity with HAND_RISE type
        }

        // Listener for the Plank button
        binding.btnPlank.setOnClickListener {
            openCameraActivity("PLANK") // Start MainActivity with PLANK type
        }

        // Listener for the Side Squat button
        binding.btnSquatSide.setOnClickListener {
            openCameraActivity("SIDE_SQUAT") // Start MainActivity with SIDE_SQUAT type
        }

        // Listener for the Help button
        binding.btnHelp.setOnClickListener {
            val intent = Intent(this, HelpActivity::class.java)
            startActivity(intent) // Navigate to the HelpActivity
        }

        // Listener for the Scores/History button
        binding.btnScores.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent) // Navigate to the HistoryActivity
        }
    }

    /**
     * Creates an Intent to start the [MainActivity] (camera/analysis screen)
     * and passes the selected exercise type as an extra.
     *
     * Input: String (exerciseType - e.g., "SQUAT", "POSE")
     * Output: Unit (Starts MainActivity and HelpActivity)
     */
    private fun openCameraActivity(exerciseType: String) {
        // Intent to start the main analysis activity
        val intent = Intent(this, MainActivity::class.java).apply {
            // Pass the exercise type so MainActivity knows which analysis to run
            putExtra("extra_exercise_type", exerciseType)
        }
        val intentHelp = Intent(this, HelpActivity::class.java) // Creates a second intent to HelpActivity
        startActivity(intent) // Starts the MainActivity
        startActivity(intentHelp) // Starts the HelpActivity immediately after
    }
}