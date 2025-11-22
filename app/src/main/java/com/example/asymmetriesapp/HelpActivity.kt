package com.example.asymmetriesapp

import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity responsible for displaying help or informational content to the user.
 * It provides a mechanism to easily close itself and return to the previous activity.
 */
class HelpActivity : AppCompatActivity(){

    /**
     * Initializes the activity and sets up the listener for the close button.
     * Input: Bundle? (savedInstanceState)
     * Output: Unit (Activity initialization)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.help_layout)  // connect your XML

        // Close button
        val btnClose = findViewById<ImageButton>(R.id.btnClose)
        btnClose.setOnClickListener {
            // The finish() method closes the current activity and returns to the calling activity
            finish()
        }

    }
}