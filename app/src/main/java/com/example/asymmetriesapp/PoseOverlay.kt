package com.example.asymmetriesapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mediapipe.formats.proto.LandmarkProto

class PoseOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ML Kit
    private var poseML: Pose? = null
    // MediaPipe
    private var poseMP: List<LandmarkProto.NormalizedLandmark>? = null

    private var isFrontAnalysis: Boolean = true
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    private val pointPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    // ML Kit pose update
    fun updatePose(
        pose: Pose,
        isFrontAnalysis: Boolean,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.poseML = pose
        this.poseMP = null
        this.isFrontAnalysis = isFrontAnalysis
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    // Mediapipe pose update
    fun updatePoseMediapipe(
        landmarks: List<LandmarkProto.NormalizedLandmark>,
        isFrontAnalysis: Boolean,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.poseMP = landmarks
        this.poseML = null
        this.isFrontAnalysis = isFrontAnalysis
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) return

        // Calculate scaling factors
        val scaleX = width.toFloat() / imageWidth.toFloat()
        val scaleY = height.toFloat() / imageHeight.toFloat()

        canvas.save()
        // Mirror the canvas for front-facing camera
        canvas.scale(-1f, 1f, width / 2f, height / 2f)

        when {
            poseML != null -> drawMLKit(canvas, poseML!!, scaleX, scaleY)
            poseMP != null -> drawMediaPipe(canvas, poseMP!!)
        }
    }

    private fun drawMLKit(canvas: Canvas, pose: Pose, scaleX: Float, scaleY: Float) {
        val landmarks = listOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
            PoseLandmark.LEFT_EAR, PoseLandmark.RIGHT_EAR
        )

        landmarks.forEach { type ->
            val lm = pose.getPoseLandmark(type)
            if (lm != null && lm.inFrameLikelihood > 0.7f) {
                canvas.drawCircle(
                    lm.position.x * scaleX,
                    lm.position.y * scaleY,
                    10f,
                    pointPaint
                )
            }
        }
    }

    private val TARGET_MEDIAPIPE_LANDMARKS = setOf(
        11, // LEFT_SHOULDER
        12, // RIGHT_SHOULDER
        13, // LEFT_ELBOW
        14, // RIGHT_ELBOW
        15, // LEFT_WRIST
        16, // RIGHT_WRIST
        23, // LEFT_HIP
        24, // RIGHT_HIP
        25, // LEFT_KNEE
        26, // RIGHT_KNEE
        27, // LEFT_ANKLE
        28, // RIGHT_ANKLE
        7,  // LEFT_EAR
        8   // RIGHT_EAR
    )

    private fun drawMediaPipe(
        canvas: Canvas,
        landmarks: List<LandmarkProto.NormalizedLandmark>,
    ) {

        landmarks.withIndex().forEach { (index, lm) ->
            if (index !in TARGET_MEDIAPIPE_LANDMARKS) return@forEach

            val normalizedX = lm.y
            val normalizedY = lm.x

            val invertedNormalizedY = 1.0f - normalizedY

            val drawX = normalizedX * width.toFloat()
            val drawY = invertedNormalizedY * height.toFloat()

            if (lm.visibility > 0.1f) {
                canvas.drawCircle(drawX, drawY, 10f, pointPaint)
            }
        }
    }
}