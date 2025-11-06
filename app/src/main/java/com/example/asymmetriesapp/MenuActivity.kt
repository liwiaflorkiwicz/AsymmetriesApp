package com.example.asymmetriesapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.asymmetriesapp.databinding.ActivityMenuBinding

class MenuActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMenuBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPose.setOnClickListener {
            openCameraActivity("POSE")
        }

        binding.btnSquat.setOnClickListener {
            openCameraActivity("SQUAT")
        }

        binding.btnRises.setOnClickListener {
            openCameraActivity("HAND_RISE")
        }

        binding.btnPlank.setOnClickListener {
            openCameraActivity("PLANK")
        }

        binding.btnSquatSide.setOnClickListener {
            openCameraActivity("SIDE_SQUAT")
        }

        binding.btnHelp.setOnClickListener {
            val intent = Intent(this, HelpActivity::class.java)
            startActivity(intent)
        }

        binding.btnScores.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }
    }

    private fun openCameraActivity(exerciseType: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("extra_exercise_type", exerciseType)
        }
        startActivity(intent)
    }
}