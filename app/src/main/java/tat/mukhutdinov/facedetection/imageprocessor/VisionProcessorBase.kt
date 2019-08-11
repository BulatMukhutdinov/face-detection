package tat.mukhutdinov.facedetection.imageprocessor

import android.graphics.Bitmap
import androidx.annotation.GuardedBy
import com.google.android.gms.tasks.Task
import com.google.firebase.ml.vision.common.FirebaseVisionImage
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata
import tat.mukhutdinov.facedetection.common.BitmapUtils
import tat.mukhutdinov.facedetection.common.CameraSource.CAMERA_FACING_BACK
import tat.mukhutdinov.facedetection.common.FrameMetadata
import tat.mukhutdinov.facedetection.common.GraphicOverlay
import tat.mukhutdinov.facedetection.common.VisionImageProcessor
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * Abstract base class for ML Kit frame processors. Subclasses need to implement {@link
 * #onSuccess(T, FrameMetadata, GraphicOverlay)} to define what they want to with the detection
 * results and {@link #detectInImage(FirebaseVisionImage)} to specify the detector object.
 *
 * @param <T> The type of the detected feature.
 */
abstract class VisionProcessorBase<T> : VisionImageProcessor {

    // To keep the latest images and its metadata.
    @GuardedBy("this")
    private var latestImage: ByteBuffer? = null

    @GuardedBy("this")
    private var latestImageMetaData: FrameMetadata? = null

    // To keep the images and metadata in process.
    @GuardedBy("this")
    private var processingImage: ByteBuffer? = null

    @GuardedBy("this")
    private var processingMetaData: FrameMetadata? = null

    @Synchronized
    override fun process(
        data: ByteBuffer,
        frameMetadata: FrameMetadata,
        graphicOverlay: GraphicOverlay
    ) {
        latestImage = data
        latestImageMetaData = frameMetadata
        if (processingImage == null && processingMetaData == null) {
            processLatestImage(graphicOverlay)
        }
    }

    // Bitmap version
    override fun process(bitmap: Bitmap, graphicOverlay: GraphicOverlay, showOnlyContour: Boolean) {
        Timber.e("Size is ${bitmap.height * bitmap.rowBytes}")
        detectInVisionImage(
            bitmap, /* bitmap */
            FirebaseVisionImage.fromBitmap(bitmap),
            FrameMetadata.Builder()
                .setWidth(bitmap.width)
                .setHeight(bitmap.height)
                .setRotation(0)
                .setCameraFacing(CAMERA_FACING_BACK)
                .build(),
            graphicOverlay,
            showOnlyContour
        )
    }

    @Synchronized
    private fun processLatestImage(graphicOverlay: GraphicOverlay) {
        processingImage = latestImage
        processingMetaData = latestImageMetaData
        latestImage = null
        latestImageMetaData = null
        if (processingImage != null && processingMetaData != null) {
            processImage(processingImage!!, processingMetaData!!, graphicOverlay)
        }
    }

    private fun processImage(
        data: ByteBuffer,
        frameMetadata: FrameMetadata,
        graphicOverlay: GraphicOverlay
    ) {
        val metadata = FirebaseVisionImageMetadata.Builder()
            .setFormat(FirebaseVisionImageMetadata.IMAGE_FORMAT_NV21)
            .setWidth(frameMetadata.width)
            .setHeight(frameMetadata.height)
            .setRotation(frameMetadata.rotation)
            .build()

        val bitmap = BitmapUtils.getBitmap(data, frameMetadata)

        detectInVisionImage(
            bitmap, FirebaseVisionImage.fromByteBuffer(data, metadata), frameMetadata,
            graphicOverlay
        )
    }

    private fun detectInVisionImage(
        originalCameraImage: Bitmap?,
        image: FirebaseVisionImage,
        metadata: FrameMetadata,
        graphicOverlay: GraphicOverlay,
        showOnlyContour: Boolean = false
    ) {
        detectInImage(image)
            .addOnSuccessListener { results ->
                onSuccess(
                    originalCameraImage, results,
                    metadata,
                    graphicOverlay,
                    showOnlyContour
                )
                processLatestImage(graphicOverlay)
            }
            .addOnFailureListener { e -> onFailure(e) }
    }

    override fun stop() {}

    abstract fun detectInImage(image: FirebaseVisionImage): Task<T>

    /**
     * Callback that executes with a successful detection result.
     *
     * @param originalCameraImage hold the original image from camera, used to draw the background
     * image.
     */
    protected abstract fun onSuccess(
        originalCameraImage: Bitmap?,
        results: T,
        frameMetadata: FrameMetadata,
        graphicOverlay: GraphicOverlay,
        showOnlyContour: Boolean = false
    )

    protected abstract fun onFailure(e: Exception)
}