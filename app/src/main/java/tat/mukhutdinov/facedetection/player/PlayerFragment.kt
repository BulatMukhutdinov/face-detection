package tat.mukhutdinov.facedetection.player

import android.animation.TimeAnimator
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Pair
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import kotlinx.android.synthetic.main.player.faceOverlay
import kotlinx.android.synthetic.main.player.preview
import kotlinx.android.synthetic.main.player.root
import kotlinx.android.synthetic.main.player.texture
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tat.mukhutdinov.facedetection.R
import tat.mukhutdinov.facedetection.common.CameraSource
import tat.mukhutdinov.facedetection.common.VisionImageProcessor
import tat.mukhutdinov.facedetection.imageprocessor.FaceDetectionProcessor
import tat.mukhutdinov.facedetection.imageprocessor.MediaCodecWrapper
import timber.log.Timber
import java.nio.ByteBuffer
import kotlin.math.max

class PlayerFragment : Fragment(), CoroutineScope by CoroutineScope(Dispatchers.Default), MediaCodecWrapper.OutputSampleListener {

    private val args: PlayerFragmentArgs by navArgs()
    private var isLandScape: Boolean = false

    // Max width (portrait mode)
    private var imageMaxWidth = 0
    // Max height (portrait mode)
    private var imageMaxHeight = 0
    private val imageProcessor: VisionImageProcessor by lazy { FaceDetectionProcessor(resources, false) }

    val handler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable)
    }

    private val timeAnimator = TimeAnimator()
    private var codecWrapper: MediaCodecWrapper? = null
    private val extractor = MediaExtractor()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.player, container, false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        isLandScape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        setupPlayback()
    }

    // Returns max image width, always for portrait mode. Caller needs to swap width / height for
    // landscape mode.
    private fun getImageMaxWidth(): Int {
        if (imageMaxWidth == 0) {
            // Calculate the max width in portrait mode. This is done lazily since we need to wait for
            // a UI layout pass to get the right values. So delay it to first time image rendering time.
            imageMaxWidth = if (isLandScape) {
                root.height
            } else {
                root.width
            }
        }

        return imageMaxWidth
    }

    // Returns max image height, always for portrait mode. Caller needs to swap width / height for
    // landscape mode.
    private fun getImageMaxHeight(): Int {
        if (imageMaxHeight == 0) {
            // Calculate the max width in portrait mode. This is done lazily since we need to wait for
            // a UI layout pass to get the right values. So delay it to first time image rendering time.
            imageMaxHeight = if (isLandScape) {
                root.width
            } else {
                root.height
            }
        }

        return imageMaxHeight
    }

    private fun getTargetedWidthHeight(): Pair<Int, Int> {
        var targetWidth = 0
        var targetHeight = 0

        val maxWidthForPortraitMode = getImageMaxWidth()
        val maxHeightForPortraitMode = getImageMaxHeight()

        targetWidth = if (isLandScape) maxHeightForPortraitMode else maxWidthForPortraitMode
        targetHeight = if (isLandScape) maxWidthForPortraitMode else maxHeightForPortraitMode

        return Pair(targetWidth, targetHeight)
    }

    override fun outputSample(sender: MediaCodecWrapper, info: MediaCodec.BufferInfo, buffer: ByteBuffer) {
        launch(handler) {
            val resized = Bitmap.createScaledBitmap(texture.bitmap, 320, 180, true)
            imageProcessor.process(resized, faceOverlay)
        }
    }

    private fun play() {
        val videoUri = Uri.parse(args.path)

        // BEGIN_INCLUDE(initialize_extractor)
        extractor.setDataSource(requireContext(), videoUri, null)
        val nTracks = extractor.trackCount

        // Begin by unselecting all of the tracks in the extractor, so we won't see
        // any tracks that we haven't explicitly selected.
        for (i in 0 until nTracks) {
            extractor.unselectTrack(i)
        }

        // Find the first video track in the stream. In a real-world application
        // it's possible that the stream would contain multiple tracks, but this
        // sample assumes that we just want to play the first one.
        for (i in 0 until nTracks) {
            // Try to create a video codec for this track. This call will return null if the
            // track is not a video track, or not a recognized video format. Once it returns
            // a valid MediaCodecWrapper, we can break out of the loop.
            codecWrapper = MediaCodecWrapper.fromVideoFormat(
                extractor.getTrackFormat(i),
                Surface(texture.surfaceTexture)
            )
            if (codecWrapper != null) {
                extractor.selectTrack(i)
                break
            }
        }
        // END_INCLUDE(initialize_extractor)

        codecWrapper?.setOutputSampleListener(this)

        // By using a {@link TimeAnimator}, we can sync our media rendering commands with
        // the system display frame rendering. The animator ticks as the {@link Choreographer}
        // receives VSYNC events.
        timeAnimator.setTimeListener(TimeAnimator.TimeListener { animation, totalTime, deltaTime ->
            val isEos = extractor.getSampleFlags() and MediaCodec
                .BUFFER_FLAG_END_OF_STREAM == MediaCodec.BUFFER_FLAG_END_OF_STREAM

            // BEGIN_INCLUDE(write_sample)
            if (!isEos) {
                // Try to submit the sample to the codec and if successful advance the
                // extractor to the next available sample to read.
                val result = codecWrapper?.writeSample(
                    extractor, false,
                    extractor.sampleTime, extractor.sampleFlags
                )

                if (result == true) {
                    // Advancing the extractor is a blocking operation and it MUST be
                    // executed outside the main thread in real applications.
                    extractor.advance()
                }
            }
            // END_INCLUDE(write_sample)

            // Examine the sample at the head of the queue to see if its ready to be
            // rendered and is not zero sized End-of-Stream record.
            val outBufferInfo = MediaCodec.BufferInfo()
            codecWrapper?.peekSample(outBufferInfo)

            // BEGIN_INCLUDE(render_sample)
            if (outBufferInfo.size <= 0 && isEos) {
                timeAnimator.end()
                codecWrapper?.stopAndRelease()
                extractor.release()
            } else if (outBufferInfo.presentationTimeUs / 1000 < totalTime) {
                // Pop the sample off the queue and send it to {@link Surface}
                codecWrapper?.popSample(true)
            }
            // END_INCLUDE(render_sample)
        })

        // We're all set. Kick off the animator to process buffers and render video frames as
        // they become available
        timeAnimator.start()
    }

    private fun setupPlayback() {
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                root.viewTreeObserver.removeOnGlobalLayoutListener(this)

                val mediaMetadataRetriever = MediaMetadataRetriever()
                mediaMetadataRetriever.setDataSource(requireContext(), Uri.parse(args.path))
                val imageBitmap = mediaMetadataRetriever.getFrameAtTime(1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

                val targetedSize = getTargetedWidthHeight()
                val targetWidth = targetedSize.first
                val maxHeight = targetedSize.second
                val scaleFactor = max(imageBitmap.width.toFloat() / targetWidth.toFloat(), imageBitmap.height.toFloat() / maxHeight.toFloat())

                val w = (imageBitmap.width / scaleFactor).toInt()
                val h = (imageBitmap.height / scaleFactor).toInt()

                setupParams(texture, w, h)
                setupParams(preview, w, h)
                setupParams(faceOverlay, w, h)

                faceOverlay.setCameraInfo(320, 180, CameraSource.CAMERA_FACING_BACK)

                preview.setImageBitmap(imageBitmap)

                preview.setOnClickListener {
                    play()
                }
            }
        })
    }

    private fun setupParams(view: View, width: Int, height: Int) {
        val params = view.layoutParams
        params.width = width
        params.height = height
        view.layoutParams = params
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
}