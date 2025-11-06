package com.example.asymmetriesapp

// PoseNet imports
import org.tensorflow.lite.Interpreter
// other imports
import java.io.File
import java.io.FileWriter
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import android.util.Log
import java.nio.MappedByteBuffer
import android.content.Context
import android.graphics.Bitmap
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.pow
import android.util.Half

class PoseDetectorTensorFlow(private val context: Context) {

    // TensorFlow Lite Interpreter for PoseNet
    private val interpreter: Interpreter by lazy {
        val options = Interpreter.Options().apply {
            setNumThreads(4)
        }
        Interpreter(loadModelFile(context, "movenet_thunder_float16_256.tflite"), options)
    }
    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fd = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fd.startOffset,
            fd.declaredLength
        )
    }

    data class PoseNetKeypoint(val x: Float, val y: Float, val score: Float)

    class PoseNetPose(output: Array<FloatArray>) {
        val keypoints = mutableMapOf<String, PoseNetKeypoint>()

        init {
            val points = listOf(
                "nose", "leftEye", "rightEye",
                "leftEar", "rightEar",
                "leftShoulder", "rightShoulder",
                "leftElbow", "rightElbow",
                "leftWrist", "rightWrist",
                "leftHip", "rightHip",
                "leftKnee", "rightKnee",
                "leftAnkle", "rightAnkle"
            )

            for (i in points.indices) {
                val (y, x, score) = output[i]
                keypoints[points[i]] = PoseNetKeypoint(x, y, score)
            }
        }

        fun keypointVisible(name: String): Boolean =
            (keypoints[name]?.score ?: 0f) > 0.5f
    }

    var csvWriter: FileWriter? = null
    var isRecording = false
    private var latestPose: PoseNetPose? = null
    private var exerciseType: String = "POSE"

    private fun isSideAnalysis() = exerciseType in listOf("SIDE_SQUAT", "PLANK")

    val asymmetryPairs = listOf(
        Pair("leftShoulder", "rightShoulder") to "shoulder",
        Pair("leftHip", "rightHip") to "hip",
        Pair("leftKnee", "rightKnee") to "knee",
        Pair("leftAnkle", "rightAnkle") to "ankle",
        Pair("leftElbow", "rightElbow") to "elbow",
        Pair("leftWrist", "rightWrist") to "wrist",
        Pair("leftEar", "rightEar") to "ear"
    )

    fun getCurrentPoseTF(): PoseNetPose? = latestPose

    /**
     * Check if pose is visible - requires at least one ear, wrist, and foot
     * This is used during the countdown phase to verify proper body positioning
     */
    fun isPoseVisibleTF(pose: PoseNetPose): Boolean {
        val earVisible = pose.keypointVisible("leftEar") || pose.keypointVisible("rightEar")
        val wristVisible = pose.keypointVisible("leftWrist") || pose.keypointVisible("rightWrist")
        val ankleVisible = pose.keypointVisible("leftAnkle") || pose.keypointVisible("rightAnkle")

        return earVisible && wristVisible && ankleVisible
    }

    /**
     * Detect pose in the given image and handle success/failure via callbacks
     */
    private val TAG = "PoseDetectorTF"

    fun detectPoseTF(bitmap: Bitmap, exerciseType: String, onSuccess: (PoseNetPose) -> Unit, onFailure: (Exception) -> Unit) {
        Log.d(TAG, "detectPoseTF() called for $exerciseType, bitmap=${bitmap.width}x${bitmap.height}")
        try {
            val byteBuffer = preprocessImageTF(bitmap)
            Log.v(TAG, "Input tensor prepared.")

            val output = Array(1) { Array(1) { Array(17) { FloatArray(3) } } }

            interpreter.run(byteBuffer, output)
            Log.v(TAG, "Interpreter run successful.")

            val pose = PoseNetPose(output[0][0])
            latestPose = pose

            if (isRecording) {
                Log.v(TAG, "Saving keypoints to CSV...")
                saveKeypointsToCSVTensorFlow(pose)
            } else {
                Log.v(TAG, "Recording inactive, skipping CSV save.")
            }

            onSuccess(pose)
        } catch (e: Exception) {
            Log.e(TAG, "detectPoseTF() failed: ${e.message}", e)
            onFailure(e)
        }
    }

    private fun preprocessImageTF(bitmap: Bitmap): ByteBuffer {
        val inputSize = 256
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        // Float16 model: use float32 input, normalized [0,1]
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 2)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        resized.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

        var idx = 0
        for (i in 0 until inputSize) {
            for (j in 0 until inputSize) {
                val pixel = intValues[idx++]
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f
                byteBuffer.putShort(floatToHalf(r))
                byteBuffer.putShort(floatToHalf(g))
                byteBuffer.putShort(floatToHalf(b))
            }
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    // Uniwersalna funkcja do konwersji float -> float16
    private fun floatToHalf(value: Float): Short {
        val intBits = java.lang.Float.floatToIntBits(value)
        val sign = (intBits ushr 16) and 0x8000
        val valExp = ((intBits ushr 23) and 0xFF) - 112
        val mant = intBits and 0x7FFFFF

        return when {
            valExp <= 0 -> sign.toShort()
            valExp >= 31 -> (sign or 0x7C00).toShort()
            else -> (sign or (valExp shl 10) or (mant shr 13)).toShort()
        }
    }

    private fun saveKeypointsToCSVTensorFlow(pose: PoseNetPose) {
        try {
            val timestamp = System.currentTimeMillis()
            val row = buildString {
                append("$timestamp,$exerciseType,")

                // If front analysis
                asymmetryPairs.forEach { (pair, _) ->
                    val leftKp = pose.keypoints[pair.first]
                    val rightKp = pose.keypoints[pair.second]

                    if (areAllVisibleTF(leftKp, rightKp)) {
                        val asymmetry = abs(leftKp!!.y - rightKp!!.y)
                        append("$asymmetry,")
                    } else {
                        append("NaN,")
                    }
                }

                // If side analysis
                if (isSideAnalysis()) {
                    when (exerciseType) {
                        "SIDE_SQUAT" -> {
                            val leftHip = pose.keypoints["leftHip"]
                            val leftKnee = pose.keypoints["leftKnee"]
                            val leftAnkle = pose.keypoints["leftAnkle"]

                            val rightHip = pose.keypoints["rightHip"]
                            val rightKnee = pose.keypoints["rightKnee"]
                            val rightAnkle = pose.keypoints["rightAnkle"]

                            if (areAllVisibleTF(leftHip, leftKnee, leftAnkle)) {
                                val leftKneeAngle = calculate3PointAngleTF(leftHip, leftKnee, leftAnkle)
                                append("${leftKneeAngle ?: "NaN"},")
                            } else if (areAllVisibleTF(rightHip, rightKnee, rightAnkle)) {
                                val rightKneeAngle = calculate3PointAngleTF(rightHip, rightKnee, rightAnkle)
                                append("${rightKneeAngle ?: "NaN"},")
                            } else {
                                append("NaN,")
                            }
                        }
                        "PLANK" -> {
                            val leftShoulder = pose.keypoints["leftShoulder"]
                            val leftHip = pose.keypoints["leftHip"]
                            val leftAnkle = pose.keypoints["leftAnkle"]

                            val rightShoulder = pose.keypoints["rightShoulder"]
                            val rightHip = pose.keypoints["rightHip"]
                            val rightAnkle = pose.keypoints["rightAnkle"]

                            if (areAllVisibleTF(leftShoulder, leftHip, leftAnkle)) {
                                val leftBodyAngle = calculate3PointAngleTF(leftShoulder, leftHip, leftAnkle)
                                append("${leftBodyAngle ?: "NaN"},")
                            } else if (areAllVisibleTF(rightShoulder, rightHip, rightAnkle)) {
                                val rightBodyAngle = calculate3PointAngleTF(rightShoulder, rightHip, rightAnkle)
                                append("${rightBodyAngle ?: "NaN"},")
                            } else {
                                append("NaN,")
                            }
                        }
                    }
                } else {
                    // Front analysis - both angles as NaN
                    append("NaN,NaN,")
                }

                // Coordinates for all landmarks (always present)
                asymmetryPairs.forEach { (pair, _) ->
                    val leftKp = pose.keypoints[pair.first]
                    val rightKp = pose.keypoints[pair.second]

                    if (isKeypointVisibleTF(leftKp) && leftKp != null) {
                        append("$leftKp.x,$leftKp.y,")
                    } else {
                        append("NaN,NaN")
                    }

                    if (isKeypointVisibleTF(rightKp) && rightKp != null) {
                        append("$rightKp.x,$rightKp.y,")
                    } else {
                        append("NaN,NaN")
                    }
                }
                append("\n")
            }
            csvWriter?.write(row)
            csvWriter?.flush()
        }
        catch (e: Exception) {
            Log.e("TAG", "saveKeypointsToCSV: ${e.message}")
        }
    }

    fun stopSavingKeypoints() {
        try {
            csvWriter?.flush()
            csvWriter?.close()
            csvWriter = null
            isRecording = false
        } catch (e: Exception) {
            Log.e("PoseDetector", "stopSavingKeypoints: ${e.message}")
        }
    }

    /**
     * Calculate 3-point angle (like in AsymmetryAnalyzer)
     * Returns the angle at pointB formed by points A-B-C
     */
    private fun calculate3PointAngleTF(a: PoseNetKeypoint?, b: PoseNetKeypoint?, c: PoseNetKeypoint?): Float? {
        if (a == null || b == null || c == null) return null

        val ab = floatArrayOf(b.x - a.x, b.y - a.y)
        val cb = floatArrayOf(b.x - c.x, b.y - c.y)

        val dot = ab[0] * cb[0] + ab[1] * cb[1]
        val magAB = sqrt(ab[0].pow(2) + ab[1].pow(2))
        val magCB = sqrt(cb[0].pow(2) + cb[1].pow(2))
        if (magAB == 0f || magCB == 0f) return null

        val cos = (dot / (magAB * magCB)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cos).toDouble()).toFloat()
    }

    private fun isKeypointVisibleTF(kp: PoseNetKeypoint?): Boolean {
        return kp != null && kp.score > 0.5f
    }

    private fun areAllVisibleTF(vararg kps: PoseNetKeypoint?): Boolean {
        return kps.all { it != null && it.score > 0.5f }
    }

    fun close() {
        interpreter.close()
        csvWriter?.close()
        stopSavingKeypoints()
    }

}

