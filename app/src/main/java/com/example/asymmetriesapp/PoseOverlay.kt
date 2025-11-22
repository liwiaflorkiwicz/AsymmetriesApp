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

/**
 * Custom View responsible for drawing pose landmarks (dots) over the camera preview.
 * It supports drawing landmarks from both ML Kit and MediaPipe detection models.
 */
class PoseOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ML Kit pose data container
    private var poseML: Pose? = null
    // MediaPipe pose data container (list of normalized landmarks)
    private var poseMP: List<LandmarkProto.NormalizedLandmark>? = null

    private var isFrontAnalysis: Boolean = true // Flag to determine analysis type (front or side)
    private var imageWidth: Int = 0 // Width of the input image frame from the camera
    private var imageHeight: Int = 0 // Height of the input image frame from the camera

    // Paint object for drawing the landmark points
    private val pointPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /**
     * Updates the pose data using results from the ML Kit model and triggers a redraw.
     *
     * Input:
     * - pose: The detected [Pose] object from ML Kit.
     * - isFrontAnalysis: Boolean indicating the analysis view (front-facing or side).
     * - imageWidth: The width of the source camera image.
     * - imageHeight: The height of the source camera image.
     * Output: Unit (Triggers view invalidation/redraw)
     */
    fun updatePose(
        pose: Pose,
        isFrontAnalysis: Boolean,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.poseML = pose
        this.poseMP = null // Clear MediaPipe data
        this.isFrontAnalysis = isFrontAnalysis
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate() // Request the view to redraw itself (calls onDraw)
    }

    /**
     * Updates the pose data using results from the MediaPipe model and triggers a redraw.
     *
     * Input:
     * - landmarks: List of [NormalizedLandmark]s from MediaPipe.
     * - isFrontAnalysis: Boolean indicating the analysis view (front-facing or side).
     * - imageWidth: The width of the source camera image.
     * - imageHeight: The height of the source camera image.
     * Output: Unit (Triggers view invalidation/redraw)
     */
    fun updatePoseMediapipe(
        landmarks: List<LandmarkProto.NormalizedLandmark>,
        isFrontAnalysis: Boolean,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.poseMP = landmarks
        this.poseML = null // Clear ML Kit data
        this.isFrontAnalysis = isFrontAnalysis
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate() // Request the view to redraw itself
    }

    /**
     * The core drawing method. It handles scaling, mirroring, and delegates drawing
     * based on which pose data is available.
     *
     * Input: Canvas (the drawing surface)
     * Output: Unit (Draws landmarks on the canvas)
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Prevent drawing if dimensions are zero (initialization not complete)
        if (imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) return

        // Calculate scaling factors to fit the image dimensions onto the view dimensions
        val scaleX = width.toFloat() / imageWidth.toFloat()
        val scaleY = height.toFloat() / imageHeight.toFloat()

        canvas.save()
        // Mirror the canvas along the Y-axis to correct for the front-facing camera view
        canvas.scale(-1f, 1f, width / 2f, height / 2f)

        // Draw based on the active pose model
        when {
            poseML != null -> drawMLKit(canvas, poseML!!, scaleX, scaleY)
            poseMP != null -> drawMediaPipe(canvas, poseMP!!)
        }
        canvas.restore() // Restore the canvas transformation state
    }

    /**
     * Draws landmarks detected by the ML Kit model onto the canvas.
     */
    private fun drawMLKit(canvas: Canvas, pose: Pose, scaleX: Float, scaleY: Float) {
        // List of target landmarks to be drawn
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
            val lm = pose.getPoseLandmark(type) // Get the specific landmark
            // Draw only if the landmark is found and confidence (inFrameLikelihood) is high
            if (lm != null && lm.inFrameLikelihood > 0.7f) {
                // Apply scaling factors to map normalized coordinates to screen pixels
                canvas.drawCircle(
                    lm.position.x * scaleX,
                    lm.position.y * scaleY,
                    10f, // Radius of the circle
                    pointPaint
                )
            }
        }
    }

    // Set of indices for key landmarks required from the MediaPipe output
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

    /**
     * Draws landmarks detected by the MediaPipe model onto the canvas.
     */
    private fun drawMediaPipe(
        canvas: Canvas,
        landmarks: List<LandmarkProto.NormalizedLandmark>,
    ) {

        landmarks.withIndex().forEach { (index, lm) ->
            if (index !in TARGET_MEDIAPIPE_LANDMARKS) return@forEach // Only draw target landmarks

            // MediaPipe provides landmarks in a specific normalized coordinate system (0.0 to 1.0)
            // Note: X and Y coordinates are  transposed and Y inverted in MediaPipe's camera output
            val normalizedX = lm.y
            val normalizedY = lm.x

            val invertedNormalizedY = 1.0f - normalizedY // Invert the Y coordinate for correct rendering

            // Map the normalized coordinates to the view's pixel dimensions
            val drawX = normalizedX * width.toFloat()
            val drawY = invertedNormalizedY * height.toFloat()

            // Draw only if the landmark visibility confidence is acceptable
            if (lm.visibility > 0.1f) {
                canvas.drawCircle(drawX, drawY, 10f, pointPaint)
            }
        }
    }
}