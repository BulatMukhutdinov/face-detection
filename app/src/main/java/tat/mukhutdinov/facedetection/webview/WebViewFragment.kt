package tat.mukhutdinov.facedetection.webview

import android.animation.TimeAnimator
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.player.faceOverlay
import kotlinx.android.synthetic.main.player.texture
import kotlinx.android.synthetic.main.web_view.loading
import kotlinx.android.synthetic.main.web_view.root
import kotlinx.android.synthetic.main.web_view.urlLabel
import kotlinx.android.synthetic.main.web_view.webView
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tat.mukhutdinov.facedetection.R
import tat.mukhutdinov.facedetection.common.CameraSource
import tat.mukhutdinov.facedetection.common.VisionImageProcessor
import tat.mukhutdinov.facedetection.imageprocessor.FaceDetectionProcessor
import tat.mukhutdinov.facedetection.imageprocessor.MediaCodecWrapper
import timber.log.Timber
import java.nio.ByteBuffer

class WebViewFragment : Fragment(), CoroutineScope by CoroutineScope(Dispatchers.Default),
    MediaCodecWrapper.OutputSampleListener {

    private val handler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable)
    }

    private val url = "https://www.videvo.net/videvo_files/converted/2018_04/preview/171215_C_19.mp473209.webm"

    private val imageProcessor: VisionImageProcessor by lazy { FaceDetectionProcessor(resources) }

    private val extractor = MediaExtractor()
    private val timeAnimator = TimeAnimator()
    private var codecWrapper: MediaCodecWrapper? = null
    private val mediaMetadataRetriever = MediaMetadataRetriever()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.web_view, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        urlLabel.text = url

        launch(handler) {
            mediaMetadataRetriever.setDataSource(url, hashMapOf())

            val imageBitmap = mediaMetadataRetriever.getFrameAtTime(1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

            val resized = getResizedBitmap(imageBitmap, 160)

            faceOverlay.setCameraInfo(resized.width, resized.height, CameraSource.CAMERA_FACING_BACK)

            withContext(Dispatchers.Main) {
                val params = texture.layoutParams
                params.width = root.width
                params.height = resized.height * root.width / resized.width
                texture.layoutParams = params

                faceOverlay.layoutParams = params

                startWebView()
                startDetection()
            }
        }
    }

    override fun outputSample(sender: MediaCodecWrapper?, info: MediaCodec.BufferInfo?, buffer: ByteBuffer?) {
        launch(handler) {
            val resized = getResizedBitmap(texture.bitmap, 160)
            imageProcessor.process(resized, faceOverlay, true)
        }
    }

    private fun startDetection() {
        extractor.setDataSource(url, mapOf())
        val tracks = extractor.trackCount

        for (i in 0 until tracks) {
            extractor.unselectTrack(i)
        }

        for (i in 0 until tracks) {
            codecWrapper = MediaCodecWrapper.fromVideoFormat(
                extractor.getTrackFormat(i),
                Surface(texture.surfaceTexture)
            )
            if (codecWrapper != null) {
                extractor.selectTrack(i)
                break
            }
        }

        codecWrapper?.setOutputSampleListener(this)

        timeAnimator.setTimeListener { _, totalTime, _ ->
            val isEos = extractor.sampleFlags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == MediaCodec.BUFFER_FLAG_END_OF_STREAM

            if (!isEos) {
                val result = codecWrapper?.writeSample(
                    extractor, false,
                    extractor.sampleTime, extractor.sampleFlags
                )

                if (result == true) {
                    extractor.advance()
                }
            }
            val outBufferInfo = MediaCodec.BufferInfo()
            codecWrapper?.peekSample(outBufferInfo)
            if (outBufferInfo.size <= 0 && isEos) {
                timeAnimator.end()
                codecWrapper?.stopAndRelease()
                extractor.release()
            } else if (outBufferInfo.presentationTimeUs / 1000 < totalTime) {
                codecWrapper?.popSample(true)
            }
        }
        timeAnimator.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun startWebView() {
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = true
        webView.settings.javaScriptCanOpenWindowsAutomatically = true
        webView.settings.setPluginState(WebSettings.PluginState.ON)
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.webChromeClient = WebChromeClient()
        webView.loadUrl(url)
        loading.visibility = GONE
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