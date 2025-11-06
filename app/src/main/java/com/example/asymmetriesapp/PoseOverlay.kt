package com.example.asymmetriesapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.View
// ML Kit imports
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

class PoseOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var mlPose: Pose? = null
    private var tfPose: PoseDetectorTensorFlow.PoseNetPose? = null
    private var isFrontAnalysis: Boolean = true
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var useModel: String = "TensorFlow" // "MLKit" or "TensorFlow"

    private val pointPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // Update for ML Kit Pose
    fun updatePose(
        pose: Pose,
        isFrontAnalysis: Boolean,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.mlPose = pose
        this.tfPose = null
        this.isFrontAnalysis = isFrontAnalysis
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.useModel = "MLKit"
        invalidate()
    }

    // Update for TensorFlow Pose
    fun updatePoseTF(
        poseTF: PoseDetectorTensorFlow.PoseNetPose,
        isFrontAnalysis: Boolean,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.tfPose = poseTF
        this.mlPose = null
        this.isFrontAnalysis = isFrontAnalysis
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.useModel = "TensorFlow"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) return

        val scaleX = width.toFloat() / imageWidth.toFloat()
        val scaleY = height.toFloat() / imageHeight.toFloat()

        canvas.save()
        canvas.scale(-1f, 1f, width / 2f, height / 2f) // mirror for front camera

        when (useModel) {
            "MLKit" -> mlPose?.let { drawMlKitPose(canvas, it, scaleX, scaleY) }
            "TensorFlow" -> tfPose?.let { drawTfPose(canvas, it, scaleX, scaleY) }
        }
    }

    private fun drawMlKitPose(canvas: Canvas, pose: Pose, scaleX: Float, scaleY: Float) {
        val landmarks = if (isFrontAnalysis) {
            listOf(
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
                PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
                PoseLandmark.LEFT_EAR, PoseLandmark.RIGHT_EAR
            )
        } else {
            // side analysis
            val leftVisible = isVisible(pose.getPoseLandmark(PoseLandmark.LEFT_HIP)) &&
                    isVisible(pose.getPoseLandmark(PoseLandmark.LEFT_KNEE))
            val rightVisible = isVisible(pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)) &&
                    isVisible(pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE))
            if (leftVisible) listOf(
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST,
                PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE,
                PoseLandmark.LEFT_EAR
            ) else if (rightVisible) listOf(
                PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST,
                PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE,
                PoseLandmark.RIGHT_EAR
            ) else emptyList()
        }

        landmarks.forEach { landmarkType ->
            pose.getPoseLandmark(landmarkType)?.let {
                if (isVisible(it)) {
                    canvas.drawCircle(it.position.x * scaleX, it.position.y * scaleY, 10f, pointPaint)
                }
            }
        }
    }

    private fun drawTfPose(canvas: Canvas, pose: PoseDetectorTensorFlow.PoseNetPose, scaleX: Float, scaleY: Float) {
        Log.v("drawTfPose", "Drawing pose with ${pose.keypoints.size} keypoints, scaleX=$scaleX, scaleY=$scaleY")

        val keypointsToDraw = if (isFrontAnalysis) {
            listOf(
                "leftShoulder", "rightShoulder",
                "leftElbow", "rightElbow",
                "leftWrist", "rightWrist",
                "leftHip", "rightHip",
                "leftKnee", "rightKnee",
                "leftAnkle", "rightAnkle",
                "leftEar", "rightEar"
            )
        } else {
            val leftVisible = (pose.keypoints["leftHip"]?.score ?: 0f) > 0.5f &&
                    (pose.keypoints["leftKnee"]?.score ?: 0f) > 0.5f
            val rightVisible = (pose.keypoints["rightHip"]?.score ?: 0f) > 0.5f &&
                    (pose.keypoints["rightKnee"]?.score ?: 0f) > 0.5f
            when {
                leftVisible -> listOf("leftShoulder", "leftElbow", "leftWrist", "leftHip", "leftKnee", "leftAnkle", "leftEar")
                rightVisible -> listOf("rightShoulder", "rightElbow", "rightWrist", "rightHip", "rightKnee", "rightAnkle", "rightEar")
                else -> {
                    Log.w("drawTfPose", "No visible side detected!")
                    emptyList()
                }
            }
        }

        keypointsToDraw.forEach { name ->
            val kp = pose.keypoints[name]
            if (kp == null) {
                Log.w("drawTfPose", "Keypoint $name not found in map")
                return@forEach
            }
            if (kp.score > 0.5f) {
                canvas.drawCircle(kp.x * scaleX, kp.y * scaleY, 10f, pointPaint)
            } else {
                Log.v("drawTfPose", "Keypoint $name score too low (${kp.score})")
            }
        }
    }


    private fun isVisible(landmark: PoseLandmark?): Boolean {
        return (landmark?.inFrameLikelihood ?: 0f) > 0.7f
    }
}
