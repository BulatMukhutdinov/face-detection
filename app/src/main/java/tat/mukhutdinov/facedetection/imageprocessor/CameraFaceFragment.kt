// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package tat.mukhutdinov.facedetection.imageprocessor

import android.animation.TimeAnimator
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import com.google.android.gms.common.annotation.KeepName
import kotlinx.android.synthetic.main.camera_face.fireFaceOverlay
import kotlinx.android.synthetic.main.camera_face.firePreview
import kotlinx.android.synthetic.main.camera_face.galleryOverlay
import kotlinx.android.synthetic.main.camera_face.galleryTexture
import kotlinx.android.synthetic.main.camera_face.root
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tat.mukhutdinov.facedetection.R
import tat.mukhutdinov.facedetection.common.CameraSource
import tat.mukhutdinov.facedetection.common.VisionImageProcessor
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer

@KeepName
class CameraFaceFragment : Fragment(), MediaCodecWrapper.OutputSampleListener, CoroutineScope by CoroutineScope(Dispatchers.Default) {

    private var cameraSource: CameraSource? = null

    private val args: CameraFaceFragmentArgs by navArgs()

    private val handler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable)
    }

    private val timeAnimator = TimeAnimator()
    private var codecWrapper: MediaCodecWrapper? = null
    private val extractor = MediaExtractor()

    private val imageProcessor: VisionImageProcessor by lazy { FaceDetectionProcessor(resources, true) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.camera_face, container, false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        createCameraSource()

        cameraSource?.setFacing(CameraSource.CAMERA_FACING_FRONT)

        Handler().post { setupPlayback() }

        Handler().postDelayed({ play() }, 1000)
    }

    override fun outputSample(sender: MediaCodecWrapper, info: MediaCodec.BufferInfo, buffer: ByteBuffer) {
        launch(handler) {
            val resized = Bitmap.createScaledBitmap(galleryTexture.bitmap, 320, 180, true)
            imageProcessor.process(resized, galleryOverlay)
        }
    }

    private fun play() {
        val videoUri = Uri.parse(args.path)

        extractor.setDataSource(requireContext(), videoUri, null)
        val nTracks = extractor.trackCount

        for (i in 0 until nTracks) {
            extractor.unselectTrack(i)
        }

        for (i in 0 until nTracks) {
            codecWrapper = MediaCodecWrapper.fromVideoFormat(
                extractor.getTrackFormat(i),
                Surface(galleryTexture.surfaceTexture)
            )
            if (codecWrapper != null) {
                extractor.selectTrack(i)
                break
            }
        }

        codecWrapper?.setOutputSampleListener(this)

        timeAnimator.setTimeListener { _, totalTime, _ ->
            val isEos = extractor.sampleFlags and MediaCodec
                .BUFFER_FLAG_END_OF_STREAM == MediaCodec.BUFFER_FLAG_END_OF_STREAM

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

    private fun setupPlayback() {
        val mediaMetadataRetriever = MediaMetadataRetriever()
        mediaMetadataRetriever.setDataSource(requireContext(), Uri.parse(args.path))
        val imageBitmap = mediaMetadataRetriever.getFrameAtTime(1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)

        val resized = Bitmap.createScaledBitmap(imageBitmap, 320, 180, true)

        galleryOverlay.setCameraInfo(320, 180, CameraSource.CAMERA_FACING_BACK)

        val params = galleryTexture.layoutParams
        params.width = root.width
        params.height = resized.height * root.width / resized.width
        galleryTexture.layoutParams = params

        galleryOverlay.layoutParams = params
    }

    private fun createCameraSource() {
        if (cameraSource == null) {
            cameraSource = CameraSource(activity, fireFaceOverlay)
        }

        cameraSource?.setMachineLearningFrameProcessor(FaceDetectionProcessor(resources, false))
    }

    private fun startCameraSource() {
        cameraSource?.let {
            try {
                firePreview?.start(cameraSource, fireFaceOverlay)
            } catch (e: IOException) {
                cameraSource?.release()
                cameraSource = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        startCameraSource()
    }

    override fun onPause() {
        super.onPause()
        firePreview?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraSource?.release()
        cancel()
    }
}