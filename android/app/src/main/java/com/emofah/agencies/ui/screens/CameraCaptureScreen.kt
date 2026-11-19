package com.emofah.agencies.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.emofah.agencies.camera.CapturedImageProcessor
import com.emofah.agencies.camera.PreparedCaptureResult
import com.emofah.agencies.ui.components.EmofahBadgeTone
import com.emofah.agencies.ui.components.EmofahCard
import com.emofah.agencies.ui.components.EmofahFeedbackBanner
import com.emofah.agencies.ui.components.EmofahLoadingButton
import com.emofah.agencies.ui.components.EmofahLocalImage
import com.emofah.agencies.ui.components.EmofahStatusBadge
import com.emofah.agencies.ui.theme.emofahColors
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Direct CameraX product & receipt capture screen with off-main-thread review decoding.
 */
@Composable
fun CameraCaptureScreen(
    onCancel: () -> Unit,
    onCaptureReady: (PreparedCaptureResult) -> Unit,
    framingHint: String = "Place one product inside frame · Tap to focus",
    captureButtonLabel: String = "Capture Product",
    reviewTitle: String = "Review Captured Photo",
    confirmButtonLabel: String = "Identify Product",
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val processor = remember(context) { CapturedImageProcessor(context) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequestedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasPermission = granted
        permissionRequestedOnce = true
    }

    LaunchedEffect(Unit) {
        if (!hasPermission && !permissionRequestedOnce) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    var preparedCapture by remember { mutableStateOf<PreparedCaptureResult?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }

    if (!hasPermission) {
        CameraPermissionDeniedView(
            onRequestAgain = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
                context.startActivity(intent)
            },
            onCancel = onCancel,
            modifier = modifier,
        )
        return
    }

    val currentPrepared = preparedCapture
    if (currentPrepared != null) {
        CapturedPhotoReviewView(
            result = currentPrepared,
            reviewTitle = reviewTitle,
            confirmButtonLabel = confirmButtonLabel,
            onRetake = {
                processor.discardCapture(currentPrepared)
                preparedCapture = null
                captureError = null
            },
            onCancel = {
                processor.discardCapture(currentPrepared)
                onCancel()
            },
            onUsePhoto = { onCaptureReady(currentPrepared) },
            modifier = modifier,
        )
        return
    }

    CameraXViewfinder(
        processor = processor,
        isProcessing = isProcessing,
        captureError = captureError,
        framingHint = framingHint,
        captureButtonLabel = captureButtonLabel,
        onProcessingChanged = { isProcessing = it },
        onError = { captureError = it },
        onPrepared = { result ->
            preparedCapture = result
        },
        onCancel = onCancel,
        modifier = modifier,
    )
}

@Composable
private fun CameraPermissionDeniedView(
    onRequestAgain: () -> Unit,
    onOpenSettings: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        EmofahCard(contentPadding = PaddingValues(24.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = colors.infoContainer,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.CameraAlt,
                            contentDescription = null,
                            tint = colors.info,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                }
                Text(
                    text = "Camera Access Needed",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Emofah uses your camera to photograph one product or receipt at a time for instant identification. Works completely offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                )
                EmofahLoadingButton(
                    text = "Allow Camera Access",
                    onClick = onRequestAgain,
                    height = 48.dp,
                )
                FilledTonalButton(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text("Open App Settings")
                }
                OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun CameraXViewfinder(
    processor: CapturedImageProcessor,
    isProcessing: Boolean,
    captureError: String?,
    framingHint: String,
    captureButtonLabel: String,
    onProcessingChanged: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onPrepared: (PreparedCaptureResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            boundCamera?.cameraControl?.enableTorch(false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener(
                    {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()

                        imageCapture = capture
                        try {
                            cameraProvider.unbindAll()
                            boundCamera = cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture,
                            )
                        } catch (ex: Exception) {
                            onError(ex.message ?: "Unable to open camera")
                        }
                    },
                    ContextCompat.getMainExecutor(ctx),
                )

                previewView.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        boundCamera?.cameraControl?.startFocusAndMetering(action)
                        view.performClick()
                    }
                    true
                }

                previewView
            },
        )

        // Refined HUD Framing Guide Overlay
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 28.dp)
                .fillMaxWidth()
                .height(268.dp)
                .border(
                    width = 1.5.dp,
                    color = Color.White.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(24.dp),
                ),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 14.dp, start = 12.dp, end = 12.dp),
            ) {
                Text(
                    text = framingHint,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Translucent HUD Top Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.56f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
            ) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Cancel",
                        tint = Color.White,
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = if (torchEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else Color.Black.copy(alpha = 0.56f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
            ) {
                IconButton(
                    onClick = {
                        val next = !torchEnabled
                        torchEnabled = next
                        boundCamera?.cameraControl?.enableTorch(next)
                    },
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                        contentDescription = "Toggle flash",
                        tint = Color.White,
                    )
                }
            }
        }

        // Non-blocking error notice
        if (captureError != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 76.dp, start = 16.dp, end = 16.dp),
            ) {
                Text(
                    text = captureError,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Bottom Capture Bar
        Surface(
            color = Color.Black.copy(alpha = 0.72f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp, horizontal = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isProcessing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp,
                        )
                        Text(
                            text = "Preparing photo...",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                } else {
                    Button(
                        onClick = {
                            val cap = imageCapture ?: return@Button
                            onError(null)
                            onProcessingChanged(true)
                            val rawFile = processor.createTempCaptureFile()
                            val outputOptions = ImageCapture.OutputFileOptions.Builder(rawFile).build()
                            cap.takePicture(
                                outputOptions,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                        scope.launch {
                                            val prepared = processor.prepareCapture(rawFile)
                                            onProcessingChanged(false)
                                            if (prepared != null) {
                                                onPrepared(prepared)
                                            } else {
                                                onError("Could not process captured image. Please retake.")
                                            }
                                        }
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        onProcessingChanged(false)
                                        onError(exception.message ?: "Capture failed. Please try again.")
                                    }
                                },
                            )
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black,
                        ),
                        modifier = Modifier
                            .height(54.dp)
                            .fillMaxWidth(0.78f),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = captureButtonLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CapturedPhotoReviewView(
    result: PreparedCaptureResult,
    reviewTitle: String,
    confirmButtonLabel: String,
    onRetake: () -> Unit,
    onCancel: () -> Unit,
    onUsePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.emofahColors
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = reviewTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            )
            EmofahStatusBadge(
                text = "${result.recognitionWidth}×${result.recognitionHeight}",
                tone = EmofahBadgeTone.NEUTRAL,
            )
        }

        EmofahLocalImage(
            path = result.recognitionCopyPath,
            contentDescription = "Captured product photograph",
            fallbackIcon = Icons.Outlined.CameraAlt,
            maxDimensionPx = 1024,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp)),
        )

        result.quality.warningMessage?.let { warning ->
            EmofahFeedbackBanner(
                title = "Photo Clarity Notice",
                message = warning,
                tone = EmofahBadgeTone.WARNING,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                Text("Cancel")
            }
            FilledTonalButton(
                onClick = onRetake,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Retake")
            }
        }

        EmofahLoadingButton(
            text = confirmButtonLabel,
            onClick = onUsePhoto,
            icon = Icons.Outlined.Check,
            height = 54.dp,
        )
    }
}
