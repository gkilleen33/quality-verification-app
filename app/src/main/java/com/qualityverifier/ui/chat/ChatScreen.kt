package com.qualityverifier.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.ui.appContainer
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    itemType: ItemType?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container = appContainer()
    val viewModel: ChatViewModel = viewModel(
        key = sessionId,
        factory = ChatViewModel.factory(container, sessionId, itemType),
    )

    val messages by viewModel.messages.collectAsState()
    val pending by viewModel.pending.collectAsState()
    val sending by viewModel.sending.collectAsState()
    val error by viewModel.error.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val resolvedItemType by viewModel.itemType.collectAsState()

    var draft by remember { mutableStateOf("") }
    var captureTarget by remember { mutableStateOf<File?>(null) }
    val listState = rememberLazyListState()

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        captureTarget?.let { viewModel.onCaptureResult(it, success) }
        captureTarget = null
    }

    fun launchCamera() {
        val prepared = viewModel.prepareCapture(context) ?: return
        captureTarget = prepared.second
        // A device with no camera app would otherwise throw ActivityNotFoundException
        // and take the whole app down.
        val launched = runCatching { takePicture.launch(prepared.first) }.isSuccess
        if (!launched) {
            captureTarget = null
            viewModel.onCaptureResult(prepared.second, success = false)
        }
    }

    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            // Without this the camera button just does nothing, which reads as a broken
            // app. Also covers the permanently-denied case, where the system dialog
            // never appears at all.
            viewModel.showNotice(
                "Camera permission is off, so photos can't be taken. " +
                    "You can turn it on in your phone's settings, or choose a photo instead.",
            )
        }
    }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES_PER_PICK)
    ) { uris -> viewModel.onGalleryPicked(uris) }

    // Keep the newest turn in view as the conversation grows.
    LaunchedEffect(messages.size, sending) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(resolvedItemType?.displayName ?: "Quality check") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty() && !sending) {
                    Text(
                        "Take a photo of the furniture, or describe it, and I'll check the quality.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(messages, key = { it.id }) { message -> MessageBubble(message) }
                    }
                }
            }

            if (sending) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (messages.isEmpty()) "Starting the check…" else "Looking at the furniture…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            error?.let { chatError -> ErrorRow(chatError, viewModel, onOpenSettings) }

            notice?.let { message -> NoticeRow(message, onDismiss = viewModel::dismissNotice) }

            if (pending.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(pending, key = { it.id }) { image ->
                        Box {
                            AsyncImage(
                                model = File(image.path),
                                contentDescription = "Attached photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            IconButton(
                                onClick = { viewModel.removePending(image.id) },
                                modifier = Modifier.align(Alignment.TopEnd),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove photo",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) launchCamera() else {
                                requestCameraPermission.launch(Manifest.permission.CAMERA)
                            }
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Take photo")
                    }
                    IconButton(
                        onClick = {
                            pickImages.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.Filled.PhotoLibrary, contentDescription = "Choose photo")
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Ask about this furniture") },
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            viewModel.send(draft)
                            draft = ""
                        },
                        enabled = !sending && (draft.isNotBlank() || pending.isNotEmpty()),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun ErrorRow(
    chatError: ChatError,
    viewModel: ChatViewModel,
    onOpenSettings: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                chatError.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row {
                if (chatError.kind == ChatErrorKind.AUTH) {
                    TextButton(onClick = onOpenSettings) { Text("Open Settings") }
                } else {
                    TextButton(onClick = { viewModel.retry() }) { Text("Retry") }
                }
                TextButton(onClick = { viewModel.dismissError() }) { Text("Dismiss") }
            }
        }
    }
}

/** Advisory banner with no retry — the action failed for a reason retrying won't fix. */
@Composable
private fun NoticeRow(message: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.role == Role.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
    ) {
        if (message.attachments.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(message.attachments, key = { it.id }) { attachment ->
                    AsyncImage(
                        model = File(attachment.path),
                        contentDescription = "Photo of the furniture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        if (message.text.isNotBlank()) {
            Surface(
                color = if (fromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = RoundedCornerShape(16.dp),
                tonalElevation = if (fromUser) 0.dp else 2.dp,
                modifier = Modifier.fillMaxWidth(0.92f),
            ) {
                if (fromUser) {
                    Text(
                        message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                } else {
                    MarkdownText(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

private const val MAX_IMAGES_PER_PICK = 5
