package com.vitalis.sample

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.vitalis.core.ChallengeType
import com.vitalis.core.LivenessConfig
import com.vitalis.core.LivenessResult
import com.vitalis.ui.LivenessPrompts
import com.vitalis.ui.VitalisLivenessScreen

/**
 * Kotlin sample using the drop-in Compose screen from `vitalis-ui`.
 * The button also opens a pure-Java activity to prove the API is Java-callable (§10 step 7).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { Root() }
            }
        }
    }

    @Composable
    private fun Root() {
        val context = LocalContext.current
        var running by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf("Vitalis liveness demo (Kotlin)") }
        var hasPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            )
        }
        val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            hasPermission = granted
            running = granted
            if (!granted) message = "Camera permission denied"
        }

        if (running && hasPermission) {
            val config = LivenessConfig(
                challengeTypes = setOf(
                    ChallengeType.BLINK,
                    ChallengeType.HEAD_TURN_LEFT,
                    ChallengeType.HEAD_TURN_RIGHT,
                    ChallengeType.SMILE
                ),
                requiredChallengeCount = 2
            )
            VitalisLivenessScreen(
                config = config,
                onResult = { result ->
                    running = false
                    message = when (result) {
                        is LivenessResult.Success ->
                            "PASS — confidence ${"%.2f".format(result.confidence)}, " +
                                "${result.capturedFrame.size} bytes captured"
                        is LivenessResult.Failure ->
                            "FAIL — ${LivenessPrompts.forFailure(result.reason)} (${result.reason})"
                    }
                },
                onError = { error ->
                    running = false
                    message = "Error: ${error.code} — ${error.message}"
                }
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(message)
                Button(onClick = {
                    if (hasPermission) running = true
                    else permissionLauncher.launch(Manifest.permission.CAMERA)
                }) { Text("Start liveness (Kotlin)") }
                Button(onClick = {
                    context.startActivity(Intent(context, JavaLivenessActivity::class.java))
                }) { Text("Start liveness (Java)") }
            }
        }
    }
}
