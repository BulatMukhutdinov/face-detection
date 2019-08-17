/*
 * Copyright 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package tat.mukhutdinov.facedetection.camera

import android.animation.TimeAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
import android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
import android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.Toast
import android.widget.Toast.LENGTH_SHORT
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.navArgs
import kotlinx.android.synthetic.main.camera.*
import kotlinx.coroutines.*
import tat.mukhutdinov.facedetection.R
import tat.mukhutdinov.facedetection.common.CameraSource
import tat.mukhutdinov.facedetection.common.VisionImageProcessor
import tat.mukhutdinov.facedetection.imageprocessor.FaceDetectionProcessor
import tat.mukhutdinov.facedetection.imageprocessor.MediaCodecWrapper
import timber.log.Timber
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

@Suppress("PLUGIN_WARNING")
class CameraFragment : Fragment(), CoroutineScope by CoroutineScope(Dispatchers.Default) {

    private val args: CameraFragmentArgs by navArgs()

    private val url = "https://www.videvo.net/videvo_files/converted/2018_04/preview/171215_C_19.mp473209.webm"

    private val FRAGMENT_DIALOG = "dialog"
    private val galleryTimeAnimator = TimeAnimator()
    private var galleryCodecWrapper: MediaCodecWrapper? = null
    private val galleryExtractor = MediaExtractor()

    private val webViewTimeAnimator = TimeAnimator()
    private var webViewCodecWrapper: MediaCodecWrapper? = null
    private val webViewExtractor = MediaExtractor()

    val handler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable)
    }

    private val imageProcessor: VisionImageProcessor by lazy { FaceDetectionProcessor(resources, true) }

    /**
     * [TextureView.SurfaceTextureListener] handles several lifecycle events on a
     * [TextureView].
     */
    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
    }

    /**
     * A reference to the opened [android.hardware.camera2.CameraDevice].
     */
    private var cameraDevice: CameraDevice? = null

    /**
     * A reference to the current [android.hardware.camera2.CameraCaptureSession] for
     * preview.
     */
    private var captureSession: CameraCaptureSession? = null

    /**
     * The [android.util.Size] of camera preview.
     */
    private lateinit var previewSize: Size

    /**
     * The [android.util.Size] of video recording.
     */
    private lateinit var videoSize: Size

    /**
     * Whether the app is recording video now
     */
    private var isRecordingVideo = false

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private var backgroundThread: HandlerThread? = null

    /**
     * A [Handler] for running tasks in the background.
     */
    private var backgroundHandler: Handler? = null

    /**
     * A [Semaphore] to prevent the app from exiting before closing the camera.
     */
    private val cameraOpenCloseLock = Semaphore(1)

    /**
     * [CaptureRequest.Builder] for the camera preview
     */
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    /**
     * Orientation of the camera sensor
     */
    private var sensorOrientation = 0

    /**
     * [CameraDevice.StateCallback] is called when [CameraDevice] changes its status.
     */
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@CameraFragment.cameraDevice = cameraDevice
            startPreview()
            configureTransform(cameraTexture.width, cameraTexture.height)
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraFragment.cameraDevice = null
            activity?.finish()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.camera, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        Handler().postDelayed({
            setupPlayback()
        }, 500)

        record.setOnClickListener {
            onToggleScreenShare(it)
//            if (isRecordingVideo) stopRecordingVideo() else startRecordingVideo()
        }

        launch(handler) {
            delay(1000)

            while (true) {
                val bmOverlay = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)

                Canvas(bmOverlay).apply {
                    drawBitmap(cameraTexture.bitmap, cameraTexture.x, cameraTexture.y, null)
                    drawBitmap(galleryTexture.bitmap, galleryTexture.x, galleryTexture.y, null)
                    drawBitmap(webViewTexture.bitmap, webViewTexture.x, webViewTexture.y, null)
                }

                val resized = getResizedBitmap(bmOverlay, RESIZED_IMAGE_SIZE)
                imageProcessor.process(resized, faceOverlay)

                delay(100)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (cameraTexture.isAvailable) {
            openCamera(cameraTexture.width, cameraTexture.height)
        } else {
            cameraTexture.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private fun galleryPlay() {
        val videoUri = Uri.parse(args.path)

        galleryExtractor.setDataSource(requireContext(), videoUri, null)
        val nTracks = galleryExtractor.trackCount

        for (i in 0 until nTracks) {
            galleryExtractor.unselectTrack(i)
        }

        for (i in 0 until nTracks) {
            galleryCodecWrapper = MediaCodecWrapper.fromVideoFormat(
                galleryExtractor.getTrackFormat(i),
                Surface(galleryTexture.surfaceTexture)
            )
            if (galleryCodecWrapper != null) {
                galleryExtractor.selectTrack(i)
                break
            }
        }

        galleryTimeAnimator.setTimeListener { _, totalTime, _ ->
            val isEos = galleryExtractor.sampleFlags and MediaCodec
                .BUFFER_FLAG_END_OF_STREAM == MediaCodec.BUFFER_FLAG_END_OF_STREAM

            if (!isEos) {
                val result = galleryCodecWrapper?.writeSample(
                    galleryExtractor, false,
                    galleryExtractor.sampleTime, galleryExtractor.sampleFlags
                )

                if (result == true) {
                    galleryExtractor.advance()
                }
            }
            val outBufferInfo = MediaCodec.BufferInfo()
            galleryCodecWrapper?.peekSample(outBufferInfo)

            if (outBufferInfo.size <= 0 && isEos) {
                galleryTimeAnimator.end()
                galleryCodecWrapper?.stopAndRelease()
                galleryExtractor.release()
            } else if (outBufferInfo.presentationTimeUs / 1000 < totalTime) {
                galleryCodecWrapper?.popSample(true)
            }
        }

        galleryTimeAnimator.start()
    }

    private fun webViewPlay() {
        webViewExtractor.setDataSource(url, mapOf())
        val nTracks = webViewExtractor.trackCount

        for (i in 0 until nTracks) {
            webViewExtractor.unselectTrack(i)
        }

        for (i in 0 until nTracks) {
            webViewCodecWrapper = MediaCodecWrapper.fromVideoFormat(
                webViewExtractor.getTrackFormat(i),
                Surface(webViewTexture.surfaceTexture)
            )
            if (webViewCodecWrapper != null) {
                webViewExtractor.selectTrack(i)
                break
            }
        }

        webViewTimeAnimator.setTimeListener { _, totalTime, _ ->
            val isEos = webViewExtractor.sampleFlags and MediaCodec
                .BUFFER_FLAG_END_OF_STREAM == MediaCodec.BUFFER_FLAG_END_OF_STREAM

            if (!isEos) {
                val result = webViewCodecWrapper?.writeSample(
                    webViewExtractor, false,
                    webViewExtractor.sampleTime, webViewExtractor.sampleFlags
                )

                if (result == true) {
                    webViewExtractor.advance()
                }
            }
            val outBufferInfo = MediaCodec.BufferInfo()
            webViewCodecWrapper?.peekSample(outBufferInfo)

            if (outBufferInfo.size <= 0 && isEos) {
                webViewTimeAnimator.end()
                webViewCodecWrapper?.stopAndRelease()
                webViewExtractor.release()
            } else if (outBufferInfo.presentationTimeUs / 1000 < totalTime) {
                webViewCodecWrapper?.popSample(true)
            }
        }

        webViewTimeAnimator.start()
    }

    private fun setupPlayback() {
        val mediaMetadataRetriever = MediaMetadataRetriever()
        mediaMetadataRetriever.setDataSource(requireContext(), Uri.parse(args.path))
        var imageBitmap = mediaMetadataRetriever.getFrameAtTime(1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

        var resized = getResizedBitmap(imageBitmap, RESIZED_IMAGE_SIZE)

        val galleryParams = galleryTexture.layoutParams
        galleryParams.width = root.width
        galleryParams.height = resized.height * root.width / resized.width
        galleryTexture.layoutParams = galleryParams

        mediaMetadataRetriever.setDataSource(url, hashMapOf())

        imageBitmap = mediaMetadataRetriever.getFrameAtTime(1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

        resized = getResizedBitmap(imageBitmap, RESIZED_IMAGE_SIZE)

        val viewViewParams = webViewTexture.layoutParams
        viewViewParams.width = root.width
        viewViewParams.height = resized.height * root.width / resized.width
        webViewTexture.layoutParams = viewViewParams

        var width = root.width
        var height = root.height

        val ratio = width.toFloat() / height.toFloat()
        if (ratio > 1) {
            width = RESIZED_IMAGE_SIZE
            height = (width / ratio).toInt()
        } else {
            height = RESIZED_IMAGE_SIZE
            width = (height * ratio).toInt()
        }

        faceOverlay.setCameraInfo(width, height, CameraSource.CAMERA_FACING_BACK)

        galleryPlay()
        webViewPlay()
    }

    /**
     * Starts a background thread and its [Handler].
     */
    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    /**
     * Stops the background thread and its [Handler].
     */
    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Timber.e(e.toString())
        }
    }

    /**
     * Tries to open a [CameraDevice]. The result is listened by [stateCallback].
     *
     * Lint suppression - permission is checked in [hasPermissionsGranted]
     */
    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {

        val cameraActivity = activity
        if (cameraActivity == null || cameraActivity.isFinishing) return

        val manager = cameraActivity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            val cameraId = manager.cameraIdList[1]

            // Choose the sizes for camera preview and video recording
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw RuntimeException("Cannot get available preview/video sizes")
            sensorOrientation = characteristics.get(SENSOR_ORIENTATION) ?: 0
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
            previewSize = chooseOptimalSize(
                map.getOutputSizes(SurfaceTexture::class.java),
                width, height, videoSize
            )

            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                cameraTexture.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                cameraTexture.setAspectRatio(previewSize.height, previewSize.width)
            }
            configureTransform(width, height)
            manager.openCamera(cameraId, stateCallback, null)
        } catch (e: CameraAccessException) {
            showToast("Cannot access the camera.")
            cameraActivity.finish()
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            ErrorDialog.newInstance(getString(R.string.camera_error))
                .show(childFragmentManager, FRAGMENT_DIALOG)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }

    /**
     * Close the [CameraDevice].
     */
    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSession()
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    /**
     * Start the camera preview.
     */
    private fun startPreview() {
        if (cameraDevice == null || !cameraTexture.isAvailable) return

        try {
            closePreviewSession()
            val texture = cameraTexture!!.surfaceTexture
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            previewRequestBuilder.addTarget(previewSurface)

            cameraDevice?.createCaptureSession(listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (activity != null) showToast("Failed")
                    }
                }, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Timber.e(e.toString())
        }
    }

    /**
     * Update the camera preview. [startPreview] needs to be called in advance.
     */
    private fun updatePreview() {
        if (cameraDevice == null) return

        try {
            setUpCaptureRequestBuilder(previewRequestBuilder)
            HandlerThread("CameraPreview").start()
            captureSession?.setRepeatingRequest(
                previewRequestBuilder.build(),
                null, backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Timber.e(e.toString())
        }
    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder?) {
        builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }

    /**
     * Configures the necessary [android.graphics.Matrix] transformation to `cameraTextureView`.
     * This method should not to be called until the camera preview size is determined in
     * openCamera, or until the size of `cameraTextureView` is fixed.
     *
     * @param viewWidth  The width of `cameraTextureView`
     * @param viewHeight The height of `cameraTextureView`
     */
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = (activity as FragmentActivity).windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width
            )
            with(matrix) {
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        cameraTexture!!.setTransform(matrix)
    }

    private fun getVideoFilePath(context: Context?): String {
        val filename = "${System.currentTimeMillis()}.mp4"
        val dir = context?.getExternalFilesDir(null)

        return if (dir == null) {
            filename
        } else {
            "${dir.absolutePath}/$filename"
        }
    }

    private var mScreenDensity: Int = 0
    private var mProjectionManager: MediaProjectionManager? = null
    private var mMediaProjection: MediaProjection? = null
    private var mMediaRecorder: MediaRecorder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (mMediaProjection != null) {
            mMediaProjection?.stop()
            mMediaProjection = null
        }
    }

    fun onToggleScreenShare(view: View) {
        if (!isRecordingVideo) {
        } else {
        }
    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun showToast(message: String) = Toast.makeText(activity, message, LENGTH_SHORT).show()

    /**
     * In this sample, we choose a video size with 3x4 aspect ratio. Also, we don't use sizes
     * larger than 1080p, since MediaRecorder cannot handle such a high-resolution video.
     *
     * @param choices The list of available sizes
     * @return The video size
     */
    private fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
        it.width == it.height * 4 / 3 && it.width <= 1080
    } ?: choices[choices.size - 1]

    /**
     * Given [choices] of [Size]s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values, and whose aspect
     * ratio matches with the specified value.
     *
     * @param choices     The list of sizes that the camera supports for the intended output class
     * @param width       The minimum desired width
     * @param height      The minimum desired height
     * @param aspectRatio The aspect ratio
     * @return The optimal [Size], or an arbitrary one if none were big enough
     */
    private fun chooseOptimalSize(
        choices: Array<Size>,
        width: Int,
        height: Int,
        aspectRatio: Size
    ): Size {

        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height
        }

        // Pick the smallest of those, assuming we found any
        return if (bigEnough.isNotEmpty()) {
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    private fun getResizedBitmap(image: Bitmap, maxSize: Int): Bitmap {
        var width = image.width
        var height = image.height

        val bitmapRatio = width.toFloat() / height.toFloat()
        if (bitmapRatio > 1) {
            width = maxSize
            height = (width / bitmapRatio).toInt()
        } else {
            height = maxSize
            width = (height * bitmapRatio).toInt()
        }
        return Bitmap.createScaledBitmap(image, width, height, true)
    }

    companion object {
        const val RESIZED_IMAGE_SIZE = 400
    }
}
