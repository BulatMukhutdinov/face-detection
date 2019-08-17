package tat.mukhutdinov.facedetection.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.navArgs
import kotlinx.android.synthetic.main.player2.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import tat.mukhutdinov.facedetection.R

class PlayerFragment2 : Fragment(), CoroutineScope by CoroutineScope(Dispatchers.Default) {

    private val args: PlayerFragment2Args by navArgs()

    private var player: PlayerManager? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.player2, container, false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        player = PlayerManager(requireContext(), args.path)
    }

    override fun onResume() {
        super.onResume()
        player?.init(requireContext(), playerView)
    }

    override fun onPause() {
        super.onPause()
        player?.reset()
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }

}