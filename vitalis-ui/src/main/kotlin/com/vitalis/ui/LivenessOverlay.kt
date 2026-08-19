package com.vitalis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalis.core.LivenessState

/**
 * Face-guide oval + prompt overlay driven by [LivenessState] (§5 optional UI, §10 step 6).
 * Purely presentational — the host draws it on top of the camera preview.
 *
 * The scrim is a single even-odd path (full-screen rect minus the oval) so the clear
 * "window" works without an offscreen compositing layer.
 */
@Composable
fun LivenessOverlay(
    state: LivenessState,
    modifier: Modifier = Modifier,
    ovalColor: Color = Color.White,
    scrimColor: Color = Color.Black.copy(alpha = 0.55f)
) {
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ovalWidth = size.width * 0.7f
            val ovalHeight = ovalWidth * 1.3f
            val left = (size.width - ovalWidth) / 2f
            val top = (size.height - ovalHeight) / 2.4f
            val ovalRect = Rect(Offset(left, top), Size(ovalWidth, ovalHeight))

            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addOval(ovalRect)
            }
            drawPath(scrim, color = scrimColor)
            drawOval(
                color = ovalColor,
                topLeft = ovalRect.topLeft,
                size = ovalRect.size,
                style = Stroke(width = 6f)
            )
        }
        Text(
            text = LivenessPrompts.forState(state),
            color = Color.White,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp)
        )
    }
}
