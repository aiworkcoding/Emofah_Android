package com.emofah.agencies.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.asImageBitmap
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
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Stage 1.2 (Actions 1.2.1 – 1.2.3):
 * Direct CameraX product capture screen.
 * Opens camera directly when permission is granted, works 100% offline, provides
 * framing box, tap-to-focus, torch toggle, blur/low-light warnings, Retake, and Cancel.
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
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = Icons.Outlined.CameraAlt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(52.dp),
        )
        Text(
            text = "Camera Access Needed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "Emofah needs your camera to photograph one product at a time for instant visual identification and sales capture. No internet connection is required.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRequestAgain,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Allow Camera Access")
        }
        FilledTonalButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open App Settings")
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Cancel")
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

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
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

                // Tap-to-focus support (Action 1.2.2)
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

        // Framing Guide Overlay (One product at a time)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .fillMaxWidth()
                .height(260.dp)
                .border(2.dp, Color(0xFF43B1D4), RoundedCornerShape(24.dp)),
        ) {
            Text(
                text = framingHint,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        // Top Bar: Cancel & Torch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Cancel",
                    tint = Color.White,
                )
            }

            IconButton(
                onClick = {
                    val next = !torchEnabled
                    torchEnabled = next
                    boundCamera?.cameraControl?.enableTorch(next)
                },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) {
                Icon(
                    imageVector = if (torchEnabled) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                    contentDescription = "Toggle flash",
                    tint = Color.White,
                )
            }
        }

        // Error banner if capture fails
        if (captureError != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
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

        // Bottom Capture Control
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(vertical = 22.dp, horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (isProcessing) {
                CircularProgressIndicator(color = Color.White)
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
                    shape = CircleShape,
                    modifier = Modifier.height(58.dp).fillMaxWidth(0.7f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CameraAlt,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = captureButtonLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
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
    val previewBitmap = remember(result.recognitionCopyPath) {
        BitmapFactory.decodeFile(result.recognitionCopyPath)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = reviewTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = "Captured product photograph",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.08f)),
            )
        }

        result.quality.warningMessage?.let { warning ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                    )
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Text(
            text = "Prepared ${result.recognitionWidth}×${result.recognitionHeight}px recognition copy (offline ready)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f).height(50.dp),
            ) {
                Text("Cancel")
            }
            FilledTonalButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f).height(50.dp),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Retake")
            }
        }

        Button(
            onClick = onUsePhoto,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            Icon(Icons.Outlined.Check, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(
                text = confirmButtonLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
