package com.vitalis.sample;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.vitalis.antispoof.Vitalis;
import com.vitalis.core.ChallengeType;
import com.vitalis.core.LivenessConfig;
import com.vitalis.core.LivenessDetector;
import com.vitalis.core.LivenessError;
import com.vitalis.core.LivenessListener;
import com.vitalis.core.LivenessResult;
import com.vitalis.core.LivenessState;
import com.vitalis.ui.LivenessPrompts;

import java.util.Collections;

/**
 * Pure-Java consumer of the Vitalis API (feature doc §6, §10 step 7).
 *
 * The point of this activity is to catch Kotlin/Java interop regressions: it builds a
 * {@link LivenessConfig} via the Java-friendly builder, constructs a detector with the
 * {@code @JvmStatic} factory, and implements {@link LivenessListener} as a plain Java
 * interface — no coroutines, no Kotlin default-argument gymnastics.
 */
public class JavaLivenessActivity extends AppCompatActivity implements LivenessListener {

    private static final int REQ_CAMERA = 1001;

    private LivenessDetector detector;
    private PreviewView previewView;
    private TextView statusText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_java_liveness);
        previewView = findViewById(R.id.preview);
        statusText = findViewById(R.id.status);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startLiveness();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void startLiveness() {
        // Java-friendly config: fluent builder, defaults preserved for untouched fields.
        LivenessConfig config = LivenessConfig.builder()
                .challengeTypes(Collections.singleton(ChallengeType.BLINK))
                .minFaceRatio(0.25f)
                .returnCapturedFrame(true)
                .build();

        detector = Vitalis.create(this, this, previewView);
        detector.start(config, this);
    }

    // --- LivenessListener (implemented in Java) ---------------------------

    @Override
    public void onStateChanged(@NonNull LivenessState state) {
        statusText.setText(LivenessPrompts.INSTANCE.forState(state));
    }

    @Override
    public void onResult(@NonNull LivenessResult result) {
        if (result instanceof LivenessResult.Success) {
            LivenessResult.Success success = (LivenessResult.Success) result;
            statusText.setText("PASS — confidence " + success.getConfidence());
        } else if (result instanceof LivenessResult.Failure) {
            LivenessResult.Failure failure = (LivenessResult.Failure) result;
            statusText.setText("FAIL — " + LivenessPrompts.INSTANCE.forFailure(failure.getReason()));
        }
    }

    @Override
    public void onError(@NonNull LivenessError error) {
        Toast.makeText(this, error.getCode() + ": " + error.getMessage(), Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLiveness();
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (detector != null) {
            detector.stop();
        }
    }
}
