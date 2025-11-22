package com.example.asymmetriesapp

import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity representing the main landing page (Home) of the application.
 * It primarily handles navigation to the main menu.
 */
class HomeActivity : AppCompatActivity(){

    /**
     * Initializes the activity, sets the content view, and sets up click listeners.
     * Input: android.os.Bundle? (savedInstanceState)
     * Output: Unit (Activity initialization)
     */
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.home_layout)

        // Find the button used to proceed from the home screen
        val btnHome = findViewById<android.widget.Button>(R.id.btnHome)

        btnHome.setOnClickListener {
            Log.d("HomeActivity", "Home button clicked")
            // Create an Intent to navigate from HomeActivity to MenuActivity
            val intent = Intent(this, MenuActivity::class.java)
            startActivity(intent)
        }

    }
}