package com.qualityverifier.ui.capture

import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.qualityverifier.text.markdownToPlainText
import java.io.File

/**
 * Guided capture: the instruction sits on top of the live preview.
 *
 * This is the reason the app has its own camera rather than handing off to the system
 * one. In a shop the user is holding a phone in one hand, is being watched by the person
 * who built the furniture, and has been told to frame something specific — "the joint
 * where the stretcher meets the leg, filling the frame". Sending them to a camera app
 * that has forgotten the instruction means they come back with the wrong photo, and the
 * assessment is built on it.
 *
 * The instruction shown is simply the assistant's last message, because the protocol
 * asks for exactly one photo at a time. No extra prompt machinery, and nothing to drift
 * out of sync.
 */
@Composable
fun CaptureScreen(
    instruction: String?,
    reviewPhotoPath: String?,
    warning: String?,
    createFile: () -> File?,
    onCaptured: (File) -> Unit,
    onKeep: () -> Unit,
    onRetake: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var bindError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    val capture = ImageCapture.Builder()
                        // A shed is dim, but a buyer with a phone held over an upturned
                        // stool cannot hold still for a long exposure. Latency wins;
                        // the blur check below catches what that costs.
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                    )
                    imageCapture = capture
                }.onFailure {
                    bindError = "This phone's camera could not be opened. " +
                        "You can still choose a photo from your gallery."
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { camera?.cameraControl?.enableTorch(false) } }
    }

    Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (reviewPhotoPath != null) {
                AsyncImage(
                    model = File(reviewPhotoPath),
                    contentDescription = "The photo you just took",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }

            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopBar(
                    instruction = instruction,
                    showTorch = reviewPhotoPath == null && camera?.cameraInfo?.hasFlashUnit() == true,
                    torchOn = torchOn,
                    onToggleTorch = {
                        torchOn = !torchOn
                        runCatching { camera?.cameraControl?.enableTorch(torchOn) }
                    },
                    onClose = onClose,
                )

                Spacer(Modifier.weight(1f))

                bindError?.let { message ->
                    Notice(message, Modifier.padding(16.dp))
                }

                if (reviewPhotoPath != null) {
                    ReviewControls(warning = warning, onKeep = onKeep, onRetake = onRetake)
                } else if (bindError == null) {
                    Shutter(
                        busy = busy,
                        enabled = imageCapture != null,
                        onClick = {
                            val capture = imageCapture ?: return@Shutter
                            val file = createFile() ?: return@Shutter
                            busy = true
                            capture.takePicture(
                                ImageCapture.OutputFileOptions.Builder(file).build(),
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(
                                        output: ImageCapture.OutputFileResults,
                                    ) {
                                        busy = false
                                        onCaptured(file)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        busy = false
                                        runCatching { file.delete() }
                                        bindError = "That photo could not be saved. Try again."
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    instruction: String?,
    showTorch: Boolean,
    torchOn: Boolean,
    onToggleTorch: () -> Unit,
    onClose: () -> Unit,
) {
    var truncated by remember { mutableStateOf(false) }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close the camera", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (showTorch) {
                IconButton(onClick = onToggleTorch) {
                    Icon(
                        imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                        contentDescription = if (torchOn) "Turn the light off" else "Turn the light on",
                        tint = Color.White,
                    )
                }
            }
        }
        instruction?.takeIf { it.isNotBlank() }?.let { text ->
            var expanded by remember { mutableStateOf(false) }
            Surface(
                color = Color.Black.copy(alpha = 0.65f),
                contentColor = Color.White,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .clickable { expanded = !expanded },
            ) {
                Column(Modifier.padding(14.dp)) {
                    // Clipped by whole lines, not by height. A fixed-height scrolling box
                    // sliced the last line through the middle of the glyphs, which reads
                    // as a broken screen rather than as more text below.
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (expanded) EXPANDED_LINES else COLLAPSED_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { result -> truncated = result.hasVisualOverflow },
                    )
                    if (truncated || expanded) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (expanded) "Tap to shorten" else "Tap to read it all",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Shutter(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.White)
        } else {
            // A single large target: this is pressed one-handed, often at arm's length
            // under a piece of furniture.
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(if (enabled) Color.White else Color.White.copy(alpha = 0.4f)),
            ) {
                IconButton(
                    onClick = onClick,
                    enabled = enabled,
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }
        }
    }
}

@Composable
private fun ReviewControls(warning: String?, onKeep: () -> Unit, onRetake: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        warning?.let { Notice(it, Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onRetake, modifier = Modifier.weight(1f).height(56.dp)) {
                Text("Take it again")
            }
            OutlinedButton(onClick = onKeep, modifier = Modifier.weight(1f).height(56.dp)) {
                Text("Use it anyway")
            }
        }
    }
}

@Composable
private fun Notice(message: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(14.dp),
        )
    }
}

/** Four lines leaves most of the viewfinder visible; twelve fits any single shot direction. */
private const val COLLAPSED_LINES = 4
private const val EXPANDED_LINES = 12

/**
 * The instruction to show over the preview: the assistant's own words, flattened,
 * because markdown emphasis is noise at a glance and the overlay has no room for it.
 */
fun captureInstruction(assistantText: String?): String? =
    assistantText?.takeIf { it.isNotBlank() }?.let { markdownToPlainText(it).trim() }
        ?.takeIf { it.isNotEmpty() }
