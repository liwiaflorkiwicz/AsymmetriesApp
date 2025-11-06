package com.example.asymmetriesapp

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity

class HelpActivity : AppCompatActivity(){

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.help_layout)  // connect your XML

        // Close button
        val btnClose = findViewById<ImageButton>(R.id.btnClose)
        btnClose.setOnClickListener { finish() }

    }
}