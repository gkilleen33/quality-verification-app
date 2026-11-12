package com.qualityverifier.ui.photo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.center
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.qualityverifier.text.ReportLabels
import java.io.File
import kotlin.math.abs

/**
 * One photograph the viewer can show: the file on the phone, and what the assessment
 * asked for when it was taken, when the app knows. The caption is shown under the
 * photo; null draws none.
 */
data class ViewerPhoto(
    val path: String,
    val caption: String? = null,
)

/**
 * A photograph full screen, to look closely at what the verdict describes.
 *
 * A thumbnail in the conversation is 72 dp. The verdict names a gap at a joint or saw
 * marks on a top, and at 72 dp a maker standing over the piece cannot find either on
 * the photo the finding came from. Here the photo fills the screen, and a pinch takes it
 * past its own pixels (see [PhotoFrame]). A sideways swipe goes to the assessment's
 * other photos, in the order they were taken; the close button and the system Back
 * both leave the assessment exactly where it was, since this is drawn over it rather
 * than navigated to.
 *
 * Photos are read through Coil, as every thumbnail is, from the file the assessment or
 * the sync wrote. Nothing is fetched here: a photo the sync could not download is not
 * in the conversation at all (`AssessmentSync.fetchPhoto` drops it), so there is
 * nothing for this screen to fetch. What can still fail is the decode of a file on the
 * phone, which is what the progress ring and the retry are for.
 *
 * Only the photo at rest is decoded at full size. A 2,576 px photo is some 20 MB as a
 * bitmap, and a swipe shows two pages at once, so a pager that decoded every visible
 * page at full size would hold two of them, plus whatever Coil's memory cache kept
 * from earlier pages. Every page shows a screen-sized decode, which is what the pager
 * swipes between; the page the pager has settled on draws the full-size decode over
 * it, uncached, and drops it when the pager moves on. See [ZoomablePhoto].
 *
 * Shared by both apps, like the camera it sits beside. Fundi Bora is the only caller
 * for now.
 */
@Composable
fun PhotoViewer(
    photos: List<ViewerPhoto>,
    initialIndex: Int,
    labels: ReportLabels,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (photos.isEmpty()) return
    BackHandler(onBack = onClose)

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, photos.lastIndex),
    ) { photos.size }
    // The pager's own page may outrun a list that has just shrunk, for one frame.
    val current = photos.getOrNull(pagerState.currentPage) ?: photos.last()

    Box(modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            // No page is composed until it comes into view, so a page off screen holds
            // no bitmap at all. The default, written down because the memory argument
            // above depends on it.
            beyondViewportPageCount = 0,
            key = { photos[it].path },
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            ZoomablePhoto(
                photo = photos[page],
                atRest = pagerState.settledPage == page,
                labels = labels,
            )
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = labels.closePhoto, tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            // Where a swipe leads. Pointless on an assessment with one photo.
            if (photos.size > 1) {
                Text(
                    labels.photoOf(pagerState.currentPage + 1, photos.size),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(end = 16.dp),
                )
            }
        }

        current.caption?.let { caption ->
            Text(
                caption,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * One page of the viewer: the photo, its zoom and pan, and its loading states.
 *
 * Two decodes of the same file, drawn on top of each other inside one transformed box
 * so they can never disagree about where the photo is. The first is sized to the
 * screen, which Coil samples down to on decode: a few megabytes, cached, and enough for
 * swiping. The second, only while [atRest], is the file at its own size, kept out of
 * the memory cache so that the next page's full decode does not sit beside this one.
 * While it loads the screen-sized one shows, so a pinch that starts early is not
 * punished with a blank; it only sharpens when the full decode lands.
 *
 * The zoom limits need the photo's size in pixels. The screen-sized decode reports its
 * own, smaller size first, and the full decode the true one, so the frame is rebuilt
 * from whichever is larger and the position re-clamped to the new limits.
 */
@Composable
private fun ZoomablePhoto(
    photo: ViewerPhoto,
    atRest: Boolean,
    labels: ReportLabels,
) {
    val context = LocalContext.current
    var position by remember { mutableStateOf(PhotoPosition.FITTED) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    // Bumped by the retry. The request carries it, so Coil sees a new request rather
    // than the one it has already failed.
    var attempt by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }

    val frame = remember(viewport, imageSize) {
        if (viewport.width > 0 && viewport.height > 0 && imageSize.width > 0 && imageSize.height > 0) {
            PhotoFrame(
                viewportWidth = viewport.width.toFloat(),
                viewportHeight = viewport.height.toFloat(),
                imageWidth = imageSize.width.toFloat(),
                imageHeight = imageSize.height.toFloat(),
            )
        } else {
            null
        }
    }
    // Read inside the gesture handlers through this rather than as a key on them: a
    // new frame mid-pinch (the full decode landing) must not cancel the pinch.
    val currentFrame by rememberUpdatedState(frame)
    LaunchedEffect(frame) { frame?.let { position = it.clamp(position) } }

    val noteSize = { state: AsyncImagePainter.State ->
        if (state is AsyncImagePainter.State.Success) {
            val drawable = state.result.drawable
            if (drawable.intrinsicWidth > imageSize.width || drawable.intrinsicHeight > imageSize.height) {
                imageSize = IntSize(drawable.intrinsicWidth, drawable.intrinsicHeight)
            }
        }
    }
    val previewRequest = remember(photo.path, attempt) {
        ImageRequest.Builder(context)
            .data(File(photo.path))
            .setParameter(ATTEMPT, attempt)
            .build()
    }
    val fullRequest = remember(photo.path, attempt) {
        ImageRequest.Builder(context)
            .data(File(photo.path))
            .size(Size.ORIGINAL)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .setParameter(ATTEMPT, attempt)
            .build()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val photoFrame = currentFrame ?: return@detectTapGestures
                        val centre = size.center
                        position = photoFrame.doubleTap(position, tap.x - centre.x, tap.y - centre.y)
                    },
                )
            }
            .pointerInput(Unit) {
                detectPhotoGestures(
                    takesDrag = { drag ->
                        currentFrame?.takesDrag(position, drag.x, drag.y) ?: false
                    },
                    onGesture = { centroid, pan, zoom ->
                        val photoFrame = currentFrame ?: return@detectPhotoGestures
                        val centre = size.center
                        position = photoFrame.pinch(
                            position,
                            centroidX = centroid.x - centre.x,
                            centroidY = centroid.y - centre.y,
                            zoomChange = zoom,
                            panChangeX = pan.x,
                            panChangeY = pan.y,
                        )
                    },
                )
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = position.scale
                    scaleY = position.scale
                    translationX = position.panX
                    translationY = position.panY
                },
        ) {
            AsyncImage(
                model = previewRequest,
                contentDescription = photo.caption,
                contentScale = ContentScale.Fit,
                onState = { state ->
                    preview = state
                    noteSize(state)
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (atRest) {
                AsyncImage(
                    model = fullRequest,
                    // The same picture as the one underneath, for a screen reader.
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    // A full decode that fails, on a phone that cannot hold it, leaves
                    // the screen-sized one showing. Nothing to tell the maker.
                    onState = noteSize,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        when (preview) {
            is AsyncImagePainter.State.Empty,
            is AsyncImagePainter.State.Loading,
            -> CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )

            is AsyncImagePainter.State.Error -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(labels.photoCouldNotOpen, color = Color.White, textAlign = TextAlign.Center)
                OutlinedButton(onClick = { attempt++ }) {
                    Text(labels.tryAgain, color = Color.White)
                }
            }

            is AsyncImagePainter.State.Success -> Unit
        }
    }
}

/**
 * Pinch and drag on a photo that sits inside a pager.
 *
 * Compose's `transformable` modifier is the usual tool and nearly fits: its `canPan`
 * is how a zoomable page inside a pager hands sideways drags to the pager. It reports
 * a zoom without the point it happened at, though, so a pinch on a joint at the edge
 * of the photo would zoom about the centre and carry the joint off the screen. This is
 * `transformable`'s own gesture loop, on the same foundation primitives, reporting the
 * centroid as well.
 *
 * Nothing is consumed until the gesture is past touch slop, and a drag only counts
 * towards that when [takesDrag] says the photo wants it; otherwise the pager, which
 * sees the same events after this node, takes the drag. Once consumed, the pager stops.
 */
private suspend fun PointerInputScope.detectPhotoGestures(
    takesDrag: (Offset) -> Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) {
    awaitEachGesture {
        var zoom = 1f
        var pan = Offset.Zero
        var pastTouchSlop = false
        val touchSlop = viewConfiguration.touchSlop

        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            val canceled = event.changes.any { it.isConsumed }
            if (!canceled) {
                val zoomChange = event.calculateZoom()
                val panChange = event.calculatePan()
                if (!pastTouchSlop) {
                    zoom *= zoomChange
                    pan += panChange
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                    val zoomMotion = abs(1 - zoom) * centroidSize
                    val panMotion = pan.getDistance()
                    if (zoomMotion > touchSlop || (panMotion > touchSlop && takesDrag(pan))) {
                        pastTouchSlop = true
                    }
                }
                if (pastTouchSlop) {
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (zoomChange != 1f || panChange != Offset.Zero) {
                        onGesture(centroid, panChange, zoomChange)
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (!canceled && event.changes.any { it.pressed })
    }
}

/** The retry counter's name on the request, so a retry is not answered from the failed one. */
private const val ATTEMPT = "attempt"
