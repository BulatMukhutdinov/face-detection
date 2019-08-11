package tat.mukhutdinov.facedetection

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import kotlinx.android.synthetic.main.home.camera
import kotlinx.android.synthetic.main.home.gallery
import kotlinx.android.synthetic.main.home.webView

class HomeFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        camera.setOnClickListener {
            findNavController().navigate(HomeFragmentDirections.toCameraFace())
//            findNavController().navigate(HomeFragmentDirections.toCamera())
        }

        webView.setOnClickListener {
            findNavController().navigate(HomeFragmentDirections.toWebView())
        }

        gallery.setOnClickListener {
            val intent = Intent()
            intent.type = "video/*"
            intent.action = Intent.ACTION_GET_CONTENT
            startActivityForResult(Intent.createChooser(intent, "Select Video"), REQUEST_TAKE_GALLERY_VIDEO)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == RESULT_OK) {
            if (requestCode == REQUEST_TAKE_GALLERY_VIDEO) {
                data?.data?.let {
                    findNavController().navigate(HomeFragmentDirections.toPlayer(it.toString()))
                }
            }
        }
    }

    companion object {
        private const val REQUEST_TAKE_GALLERY_VIDEO = 111
    }
}