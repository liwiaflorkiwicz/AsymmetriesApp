package com.example.asymmetriesapp

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

    @Entity(tableName = "exercise_results")
    data class ExerciseResultEntity(
        @PrimaryKey(autoGenerate = true) val id: Int = 0,
        val exerciseType: String,
        val csvPath: String,
        val videoPath: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val avgAsymmetry: Float? = null,
        val maxAsymmetry: Float? = null,
        val avgAngle: Float? = null,
        val maxAngle: Float? = null
    )

@Dao
interface ExerciseResultDao {

    @Insert
    suspend fun insertResult(result: ExerciseResultEntity)

    @Query("SELECT * FROM exercise_results ORDER BY timestamp DESC")
    suspend fun getAllResults(): List<ExerciseResultEntity>

}

@Database(entities = [ExerciseResultEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseResultDao(): ExerciseResultDao
}

