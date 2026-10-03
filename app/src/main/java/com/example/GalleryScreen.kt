package com.example

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.ui.theme.Inter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Pure helpers (JVM-testable, no Compose runtime)
// ─────────────────────────────────────────────────────────────────────────────

/** One day-section grouping for gallery photos. */
data class GalleryDay(
    /** yyyy-MM-dd sort key. */
    val key: String,
    /** Display label, e.g. "12 Mar 2026". */
    val label: String,
    val files: List<File>
)

/** MIME type for sharing a gallery file (DNG-aware; old code hardcoded jpeg). */
fun galleryShareMimeType(file: File): String = when (file.extension.lowercase()) {
    "dng" -> "image/x-adobe-dng"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "jpg", "jpeg" -> "image/jpeg"
    else -> "image/*"
}

fun formatGalleryDate(epochMs: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(epochMs))

private fun galleryDayKey(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(epochMs))

/** Groups newest-first photos into day sections, newest day first. */
fun groupGalleryByDay(files: List<File>): List<GalleryDay> {
    if (files.isEmpty()) return emptyList()
    val sorted = files.sortedByDescending { it.lastModified() }
    val groups = linkedMapOf<String, MutableList<File>>()
    for (f in sorted) {
        groups.getOrPut(galleryDayKey(f.lastModified())) { mutableListOf() }.add(f)
    }
    return groups.map { (key, dayFiles) ->
        GalleryDay(key = key, label = formatGalleryDate(dayFiles.first().lastModified()), files = dayFiles)
    }
}

fun galleryCounterLabel(index: Int, total: Int): String = "${index + 1} / $total"

// ─────────────────────────────────────────────────────────────────────────────
// Share plumbing (MIME-correct incl. multi-select)
// ─────────────────────────────────────────────────────────────────────────────

/** Shares one or more gallery files with MIME types derived per file. */
fun shareGalleryFiles(context: Context, files: List<File>) {
    if (files.isEmpty()) return
    try {
        val uris = files.map {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        }
        if (uris.size == 1) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = galleryShareMimeType(files.first())
                putExtra(Intent.EXTRA_STREAM, uris.first())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser_title)))
        } else {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser_title)))
        }
    } catch (e: Exception) {
        Log.e("GalleryScreen", "Error sharing photos", e)
        Toast.makeText(context, R.string.gallery_share_failed, Toast.LENGTH_SHORT).show()
    }
}

/** Decodes just the bounds (+ EXIF orientation swap) for aspect-fit sizing. */
private fun decodeGalleryAspect(photo: File): Float? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photo.absolutePath, options)
        var w = options.outWidth
        var h = options.outHeight
        if (w <= 0 || h <= 0) return null
        val orientation = try {
            ExifInterface(photo.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            w = h.also { h = w }
        }
        w.toFloat() / h.toFloat()
    } catch (_: Exception) { null }
}

// ─────────────────────────────────────────────────────────────────────────────
// Camera-chrome glass (same values as the viewfinder deck/aux chrome)
// ─────────────────────────────────────────────────────────────────────────────

private val GalleryGlassBorder: Brush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.38f),
        Color.White.copy(alpha = 0.05f),
        Color.White.copy(alpha = 0.14f)
    ),
    start = Offset.Zero,
    end = Offset.Infinite
)

private fun Modifier.galleryGlass(): Modifier = this
    .clip(RoundedCornerShape(26.dp))
    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(26.dp))
    .border(1.dp, GalleryGlassBorder, RoundedCornerShape(26.dp))

// ─────────────────────────────────────────────────────────────────────────────
// Redesigned viewer: counter/date header, EXIF chip, zoom, grid, selection
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GalleryViewer(
    photos: List<File>,
    initialIndex: Int,
    viewModel: CameraViewModel,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val selection by viewModel.gallerySelection.collectAsState()
    val canUndo by viewModel.canUndoDelete.collectAsState()

    var chromeVisible by remember { mutableStateOf(true) }
    var viewerZoom by remember { mutableStateOf(1f) }
    val dismissY = remember { androidx.compose.animation.core.Animatable(0f) }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)),
        pageCount = { photos.size }
    )

    // Keep the pager inside bounds when the list shrinks (delete / refresh).
    LaunchedEffect(photos.size) {
        if (photos.isNotEmpty() && pagerState.currentPage >= photos.size) {
            pagerState.scrollToPage((photos.size - 1).coerceAtLeast(0))
        }
    }
    // Single source of truth for selection: pager page <-> selectedPhoto.
    LaunchedEffect(pagerState.currentPage, photos.size) {
        if (photos.isNotEmpty()) {
            photos.getOrNull(pagerState.currentPage)?.let { viewModel.setSelectedPhoto(it) }
        }
    }

    val currentPhoto: File? = photos.getOrNull(pagerState.currentPage)

    // Chrome stays visible until the user taps the photo to toggle it —
    // no auto-hide timer (explicitly requested: chrome must not fade away).
    val toggleChrome: () -> Unit = { chromeVisible = !chromeVisible }
    // Undo bar auto-dismiss.
    LaunchedEffect(canUndo) {
        if (canUndo) {
            delay(5000)
            viewModel.dismissUndo()
        }
    }

    BackHandler {
        when {
            selection.isNotEmpty() -> viewModel.clearGallerySelection()
            else -> onClose()
        }
    }

    if (photos.isEmpty()) {
        GalleryEmptyState(onClose = onClose)
        return
    }

    val inSelectionMode = selection.isNotEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swipe-down-to-dismiss when unzoomed in pager mode. Coexists with
            // the horizontal pager (vertical slop only) and yields to pinch
            // zoom (disabled while zoomed).
            .pointerInput(viewerZoom, inSelectionMode) {
                if (inSelectionMode || viewerZoom > 1.01f) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dismissY.value > 220f) onClose()
                        else scope.launch { dismissY.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { dismissY.animateTo(0f) } },
                    onVerticalDrag = { change, dragAmount ->
                        if (dragAmount > 0f) {
                            scope.launch {
                                dismissY.snapTo((dismissY.value + dragAmount).coerceAtLeast(0f))
                            }
                        }
                        change.consume()
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dismissY.value
                    alpha = (1f - dismissY.value / 900f).coerceIn(0.35f, 1f)
                }
        ) {
            GalleryHeader(
                chromeVisible = chromeVisible,
                inSelectionMode = inSelectionMode,
                selectionCount = selection.size,
                totalCount = photos.size,
                currentIndex = pagerState.currentPage,
                currentPhoto = currentPhoto,
                onClose = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onClose()
                },
                onShareCurrent = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    currentPhoto?.let { shareGalleryFiles(context, listOf(it)) }
                },
                onDeleteCurrent = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    viewModel.requestDelete(currentPhoto)
                },
                onShareSelection = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    val files = photos.filter { it.absolutePath in selection }
                    shareGalleryFiles(context, files)
                    viewModel.clearGallerySelection()
                },
                onDeleteSelection = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    val files = photos.filter { it.absolutePath in selection }
                    if (files.size == 1) viewModel.requestDelete(files.first())
                    else viewModel.deleteGalleryFiles(context, files)
                },
                onClearSelection = { viewModel.clearGallerySelection() }
            )

            // Photo pager.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                beyondViewportPageCount = 1
            ) { page ->
                val photo = photos.getOrNull(page) ?: return@HorizontalPager
                GalleryZoomablePhoto(
                    photo = photo,
                    onZoomChange = { viewerZoom = it },
                    onTap = toggleChrome,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Filmstrip: follows the pager — when the selected thumb scrolls
            // out of view, the strip scrolls it back into bounds.
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                GalleryFilmstrip(
                    photos = photos,
                    currentPage = pagerState.currentPage,
                    selection = selection,
                    onThumbClick = { idx ->
                        scope.launch { pagerState.animateScrollToPage(idx) }
                    },
                    onThumbToggleSelect = { idx ->
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        photos.getOrNull(idx)?.let { viewModel.toggleGallerySelection(it.absolutePath) }
                    }
                )
            }

            // Delete-undo bar (camera chrome).
            AnimatedVisibility(visible = canUndo) {
                GalleryUndoBar(
                    onUndo = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        viewModel.undoDelete(context)
                    }
                )
            }
        }
    }
}

@Composable
private fun GalleryHeader(
    chromeVisible: Boolean,
    inSelectionMode: Boolean,
    selectionCount: Int,
    totalCount: Int,
    currentIndex: Int,
    currentPhoto: File?,
    onClose: () -> Unit,
    onShareCurrent: () -> Unit,
    onDeleteCurrent: () -> Unit,
    onShareSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onClearSelection: () -> Unit
) {
    AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .displayCutoutPadding()
                .padding(top = 16.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = if (inSelectionMode) onClearSelection else onClose,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E)),
                    modifier = Modifier.testTag(if (inSelectionMode) "gallery_selection_close" else "gallery_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close_viewfinder_desc),
                        tint = Color.White
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            inSelectionMode -> "$selectionCount ${stringResource(R.string.gallery_selected_suffix)}"
                            else -> galleryCounterLabel(currentIndex, totalCount)
                        },
                        fontSize = 15.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontFamily = Inter,
                        modifier = Modifier.testTag("gallery_counter")
                    )
                    if (!inSelectionMode && currentPhoto != null) {
                        Text(
                            text = formatGalleryDate(currentPhoto.lastModified()),
                            fontSize = 12.sp,
                            color = Color(0xFF9CA3AF),
                            fontFamily = Inter,
                            maxLines = 1
                        )
                    }
                }

                if (inSelectionMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = onShareSelection,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.share_retro_capture_desc),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onDeleteSelection,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF2A1C1C)),
                            modifier = Modifier.testTag("delete_selection_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.delete_captured_photo_desc),
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(
                            onClick = onShareCurrent,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E))
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = stringResource(R.string.share_retro_capture_desc),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onDeleteCurrent,
                            colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF2A1C1C)),
                            modifier = Modifier.testTag("delete_photo_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.delete_captured_photo_desc),
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryZoomablePhoto(
    photo: File,
    onZoomChange: (Float) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var scale by remember(photo) { mutableStateOf(1f) }
    var offset by remember(photo) { mutableStateOf(Offset.Zero) }
    var aspect by remember(photo) { mutableStateOf(1f / 1.35f) }
    LaunchedEffect(photo) {
        aspect = withContext(Dispatchers.IO) { decodeGalleryAspect(photo) } ?: (1f / 1.35f)
        scale = 1f
        offset = Offset.Zero
        onZoomChange(1f)
    }

    // Custom gesture loop (NOT Modifier.transformable): transformable
    // consumes every single-finger drag for panning even at 1x, which stole
    // horizontal swipes from the HorizontalPager and broke page changes.
    // Here single-finger drags at 1x are never consumed (pager pages), two
    // fingers pinch-zoom, and single-finger drags pan only while zoomed.
    BoxWithConstraints(
        modifier = modifier.padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        val imgWidth = minOf(maxWidth, maxHeight * aspect)
        val imgHeight = imgWidth / aspect
        Image(
            painter = rememberAsyncImagePainter(model = photo),
            contentDescription = stringResource(R.string.enlarged_capture_desc),
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .width(imgWidth)
                .height(imgHeight)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    clip = true
                }
                .pointerInput(photo) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2.5f
                            if (scale <= 1f) offset = Offset.Zero
                            onZoomChange(scale)
                        }
                    )
                }
                .pointerInput(photo) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                if (zoom != 1f || pan != Offset.Zero) {
                                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                                    scale = newScale
                                    if (newScale > 1f) offset += pan
                                    onZoomChange(newScale)
                                    pressed.forEach { it.consume() }
                                }
                            } else if (scale > 1f) {
                                // Pan the zoomed photo; consume so the pager
                                // doesn't steal the gesture mid-pan.
                                val pan = event.calculatePan()
                                if (pan != Offset.Zero) {
                                    offset += pan
                                    pressed.forEach { it.consume() }
                                }
                            }
                            // 1x single finger: consume nothing, pager pages.
                        } while (event.changes.any { it.pressed })
                    }
                }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryFilmstrip(
    photos: List<File>,
    currentPage: Int,
    selection: Set<String>,
    onThumbClick: (Int) -> Unit,
    onThumbToggleSelect: (Int) -> Unit
) {
    val context = LocalContext.current
    val stripState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Follow the pager: when the selected thumb scrolls out of the strip's
    // visible bounds, scroll it back into view.
    LaunchedEffect(currentPage, photos.size) {
        val visible = stripState.layoutInfo.visibleItemsInfo.map { it.index }
        if (visible.isEmpty() || currentPage !in visible) {
            stripState.animateScrollToItem(currentPage.coerceIn(0, (photos.size - 1).coerceAtLeast(0)))
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        LazyRow(
            state = stripState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items = photos, key = { it.absolutePath }) { item ->
                val idx = photos.indexOf(item)
                val isCurrent = idx == currentPage
                val isChecked = item.absolutePath in selection
                Box(
                    modifier = Modifier
                        .size(62.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(
                            width = if (isCurrent || isChecked) 3.dp else 0.dp,
                            color = if (isCurrent || isChecked) Color(0xFFF59E0B) else Color.Transparent,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .combinedClickable(
                            onClick = {
                                if (selection.isNotEmpty()) onThumbToggleSelect(idx)
                                else onThumbClick(idx)
                            },
                            onLongClick = { onThumbToggleSelect(idx) }
                        )
                ) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = ImageRequest.Builder(context)
                                .data(item)
                                .size(192)
                                .build()
                        ),
                        contentDescription = "${context.getString(R.string.filmstrip_photo_desc)} ${idx + 1} / ${photos.size}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (isChecked) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(3.dp)
                                .size(18.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryUndoBar(onUndo: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.galleryGlass().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.gallery_photo_deleted),
                color = Color.White,
                fontSize = 13.sp,
                fontFamily = Inter,
                modifier = Modifier.weight(1f, fill = false)
            )
            TextButton(onClick = onUndo, modifier = Modifier.testTag("gallery_undo_button")) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Undo,
                        contentDescription = null,
                        tint = Color(0xFFFBBF24),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.undo),
                        color = Color(0xFFFBBF24),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        fontFamily = Inter
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryEmptyState(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.PhotoLibrary,
            contentDescription = null,
            tint = Color(0xFF6B7280),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.gallery_empty_title),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            fontFamily = Inter,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.gallery_empty_message),
            color = Color(0xFF9CA3AF),
            fontSize = 14.sp,
            fontFamily = Inter,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onClose,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFF59E0B),
                contentColor = Color.Black
            )
        ) {
            Text(stringResource(R.string.gallery_empty_cta), fontWeight = FontWeight.Bold)
        }
    }
}
