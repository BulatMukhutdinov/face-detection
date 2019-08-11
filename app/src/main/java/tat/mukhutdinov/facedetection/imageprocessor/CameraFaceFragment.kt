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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.gms.common.annotation.KeepName
import kotlinx.android.synthetic.main.camera_face.facingSwitch
import kotlinx.android.synthetic.main.camera_face.fireFaceOverlay
import kotlinx.android.synthetic.main.camera_face.firePreview
import tat.mukhutdinov.facedetection.R
import tat.mukhutdinov.facedetection.common.CameraSource
import java.io.IOException

@KeepName
class CameraFaceFragment : Fragment() {

    private var cameraSource: CameraSource? = null

    private var isCameraFront = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.camera_face, container, false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        createCameraSource()

        facingSwitch.setOnClickListener {
            isCameraFront = !isCameraFront

            cameraSource?.let {
                if (isCameraFront) {
                    it.setFacing(CameraSource.CAMERA_FACING_FRONT)
                } else {
                    it.setFacing(CameraSource.CAMERA_FACING_BACK)
                }
            }
            firePreview?.stop()
            startCameraSource()
        }
    }

    private fun createCameraSource() {
        // If there's no existing cameraSource, create one.
        if (cameraSource == null) {
            cameraSource = CameraSource(activity, fireFaceOverlay)
        }

        cameraSource?.setMachineLearningFrameProcessor(FaceContourDetectorProcessor())
//        cameraSource?.setMachineLearningFrameProcessor(FaceDetectionProcessor(resources))
    }

    /**
     * Starts or restarts the camera source, if it exists. If the camera source doesn't exist yet
     * (e.g., because onResume was called before the camera source was created), this will be called
     * again when the camera source is created.
     */
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

    /** Stops the camera.  */
    override fun onPause() {
        super.onPause()
        firePreview?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraSource?.release()
    }
}