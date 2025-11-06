package com.example.asymmetriesapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

class PoseOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var pose: Pose? = null
    private var isFrontAnalysis: Boolean = true
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0

    private val pointPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updatePose(pose: Pose, isFrontAnalysis: Boolean, imageWidth: Int, imageHeight: Int) {
        this.pose = pose
        this.isFrontAnalysis = isFrontAnalysis
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val currentPose = pose ?: return
        if (imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) return

        // Calculate scaling factors
        val scaleX = width.toFloat() / imageWidth.toFloat()
        val scaleY = height.toFloat() / imageHeight.toFloat()

        canvas.save()
        // Mirror the canvas for front-facing camera
        canvas.scale(-1f, 1f, width / 2f, height / 2f)

        if (isFrontAnalysis) {
            drawFrontAnalysis(canvas, currentPose, scaleX, scaleY)
        } else {
            drawSideAnalysis(canvas, currentPose, scaleX, scaleY)
        }
    }

    private fun drawFrontAnalysis(canvas: Canvas, pose: Pose, scaleX: Float, scaleY: Float) {
        // Draw all visible keypoints
        val landmarks = listOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
            PoseLandmark.LEFT_EAR, PoseLandmark.RIGHT_EAR
        )

        // Draw keypoints
        landmarks.forEach { landmarkType ->
            val landmark = pose.getPoseLandmark(landmarkType)
            if (landmark != null && isVisible(landmark)) {
                canvas.drawCircle(
                    landmark.position.x * scaleX,
                    landmark.position.y * scaleY,
                    10f,
                    pointPaint
                )
            }
        }
    }

    private fun drawSideAnalysis(canvas: Canvas, pose: Pose, scaleX: Float, scaleY: Float) {
        // Determine which side is visible
        val leftHip = pose.getPoseLandmark(PoseLandmark.LEFT_HIP)
        val leftKnee = pose.getPoseLandmark(PoseLandmark.LEFT_KNEE)
        val rightHip = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP)
        val rightKnee = pose.getPoseLandmark(PoseLandmark.RIGHT_KNEE)

        val isLeftSide = leftHip != null && leftKnee != null &&
                isVisible(leftHip) && isVisible(leftKnee)
        val isRightSide = rightHip != null && rightKnee != null &&
                isVisible(rightHip) && isVisible(rightKnee)

        // Choose which side to draw
        val landmarks = if (isLeftSide) {
            listOf(
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST,
                PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE,
                PoseLandmark.LEFT_EAR
            )
        } else if (isRightSide) {
            listOf(
                PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST,
                PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE,
                PoseLandmark.RIGHT_EAR
            )
        } else {
            emptyList()
        }

        // Draw keypoints
        landmarks.forEach { landmarkType ->
            val landmark = pose.getPoseLandmark(landmarkType)
            if (landmark != null && isVisible(landmark)) {
                canvas.drawCircle(
                    landmark.position.x * scaleX,
                    landmark.position.y * scaleY,
                    10f,
                    pointPaint
                )
            }
        }
    }

    private fun isVisible(landmark: PoseLandmark): Boolean {
        return landmark.inFrameLikelihood > 0.7f
    }
}