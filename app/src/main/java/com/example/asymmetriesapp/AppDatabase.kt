package com.example.asymmetriesapp

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Data class representing an entry in the "exercise_results" database table.
 * It holds the results and metadata for a completed exercise session.
 */
@Entity(tableName = "exercise_results") // Defines the name of the table in the database
data class ExerciseResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0, // The unique identifier (Primary Key) for the result
    val exerciseType: String,
    val csvPath: String,
    val videoPath: String? = null, // Path to an optional video recording, not used in code
    val timestamp: Long = System.currentTimeMillis(), // Timestamp of when the result was recorded
    val avgAsymmetry: Float? = null,
    val maxAsymmetry: Float? = null,
    val avgAngle: Float? = null,
    val maxAngle: Float? = null
)

/**
 * Data Access Object (DAO) for the [ExerciseResultEntity].
 * This interface provides the methods for interacting with the database table.
 */
@Dao
interface ExerciseResultDao {

    /**
     * Inserts a new exercise result into the database.
     */
    @Insert
    suspend fun insertResult(result: ExerciseResultEntity) // Suspend keyword makes this a coroutine-friendly function

    /**
     * Retrieves all exercise results from the database.
     * Results are ordered by timestamp in descending order (newest first).
     * Input: None
     * Output: List<ExerciseResultEntity> (all results)
     */
    @Query("SELECT * FROM exercise_results ORDER BY timestamp DESC") // SQL query to fetch and sort data
    suspend fun getAllResults(): List<ExerciseResultEntity>
}

/**
 * The main Room Database class for the application.
 * It is the entry point for connecting to the persistent data.
 */
@Database(entities = [ExerciseResultEntity::class], version = 1) // Defines the entities and the database version
abstract class AppDatabase : RoomDatabase() {
    /**
     * Abstract function to get the DAO for [ExerciseResultEntity].
     * This is how the database exposes its data access methods.
     * Output: ExerciseResultDao (the DAO instance)
     */
    abstract fun exerciseResultDao(): ExerciseResultDao
}