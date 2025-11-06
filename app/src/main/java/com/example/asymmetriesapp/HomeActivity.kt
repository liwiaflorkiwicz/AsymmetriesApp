package com.example.asymmetriesapp

import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity(){

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.home_layout)

        val btnHome = findViewById<android.widget.Button>(R.id.btnHome)

        btnHome.setOnClickListener {
            Log.d("HomeActivity", "Home button clicked")
            val intent = Intent(this, MenuActivity::class.java)
            startActivity(intent)
        }

    }
}