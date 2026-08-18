package com.vitalis.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.vitalis.antispoof.Vitalis
import com.vitalis.core.LivenessConfig
import com.vitalis.core.LivenessError
import com.vitalis.core.LivenessListener
import com.vitalis.core.LivenessResult
import com.vitalis.core.LivenessState

/**
 * Drop-in liveness screen (§10 step 6): front-camera preview + [LivenessOverlay], wired to
 * a [Vitalis] detector for its whole composition. Requires the CAMERA permission to already
 * be granted by the host.
 *
 * The session starts when this enters composition and is torn down when it leaves.
 */
@Composable
fun VitalisLivenessScreen(
    config: LivenessConfig,
    onResult: (LivenessResult) -> Unit,
    onError: (LivenessError) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { this.clipToOutline = true } }
    var state by remember { mutableStateOf<LivenessState>(LivenessState.SearchingFace) }

    DisposableEffect(Unit) {
        val detector = Vitalis.create(context, lifecycleOwner, previewView)
        detector.start(config, object : LivenessListener {
            override fun onStateChanged(newState: LivenessState) { state = newState }
            override fun onResult(result: LivenessResult) { onResult(result) }
            override fun onError(error: LivenessError) { onError(error) }
        })
        onDispose { detector.stop() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        LivenessOverlay(state = state, modifier = Modifier.fillMaxSize())
    }
}
