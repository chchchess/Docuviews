package com.example.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.parser.PdfRendererHelper
import com.example.data.parser.PdfSearchHit
import com.example.data.parser.PdfTextWord
import com.example.domain.model.ParsedDocument
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val InvertColorFilter =
  ColorFilter.colorMatrix(
    ColorMatrix(
      floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
      )
    )
  )

private val SearchMatchFill = Color(0x4DFFA000)
private val ActiveSearchMatchFill = Color(0x88FF6D00)
private val ActiveSearchMatchStroke = Color(0xFFE65100)

@Composable
fun PdfViewerContent(
  document: ParsedDocument.Pdf,
  initialPage: Int,
  searchQuery: String,
  activeMatchIndex: Int,
  onSearchProgress: (SearchProgress) -> Unit,
  onPageChanged: (Int) -> Unit,
  onCopyFeedback: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val haptic = LocalHapticFeedback.current
  val helper =
    remember(document.file.absolutePath, document.password) {
      PdfRendererHelper(
        file = document.file,
        password = document.password,
        cacheDir = context.cacheDir,
      )
    }
  DisposableEffect(helper) { onDispose { helper.close() } }

  val totalPages = helper.pageCount.coerceAtLeast(1)
  val startIdx = initialPage.coerceIn(0, totalPages - 1)
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIdx)
  val scope = rememberCoroutineScope()
  val sharedTextMeasurer = rememberTextMeasurer(cacheSize = 256)

  val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
  val isScrolling by remember { derivedStateOf { listState.isScrollInProgress } }
  val isScrollingState = rememberUpdatedState(isScrolling)
  var isScrubbing by remember { mutableStateOf(false) }
  val isScrubbingState = rememberUpdatedState(isScrubbing)

  LaunchedEffect(currentPage) { onPageChanged(currentPage) }

  var searchHits by remember { mutableStateOf<List<PdfSearchHit>>(emptyList()) }
  val trimmedQuery = searchQuery.trim()

  LaunchedEffect(trimmedQuery, helper) {
    if (trimmedQuery.isEmpty()) {
      searchHits = emptyList()
      onSearchProgress(SearchProgress(totalMatches = 0, isSearching = false))
      return@LaunchedEffect
    }
    onSearchProgress(SearchProgress(totalMatches = 0, isSearching = true))
    delay(200)
    val results = helper.searchAllPages(trimmedQuery)
    searchHits = results
    onSearchProgress(SearchProgress(totalMatches = results.size, isSearching = false))
  }

  val activeHit =
    if (searchHits.isNotEmpty()) {
      searchHits[activeMatchIndex.coerceIn(0, searchHits.lastIndex)]
    } else {
      null
    }

  LaunchedEffect(activeHit) {
    if (activeHit != null) {
      listState.animateScrollToItem(activeHit.pageIndex)
    }
  }

  var zoomScale by remember { mutableFloatStateOf(1f) }
  var zoomJob by remember { mutableStateOf<Job?>(null) }
  var panFlingJob by remember { mutableStateOf<Job?>(null) }
  var panOffsetX by remember { mutableFloatStateOf(0f) }
  var panOffsetY by remember { mutableFloatStateOf(0f) }
  var nightInvert by remember { mutableStateOf(false) }
  var showJumpDialog by remember { mutableStateOf(false) }

  val hitsByPage = remember(searchHits) { searchHits.groupBy { it.pageIndex } }
  val textToolbar = LocalTextToolbar.current
  val focusManager = LocalFocusManager.current
  var selectionResetEpoch by remember { mutableIntStateOf(0) }
  var suppressDoubleTapUntil by remember { mutableLongStateOf(0L) }

  fun clearActiveTextSelection() {
    textToolbar.hide()
    focusManager.clearFocus(force = true)
    selectionResetEpoch++
  }

  // Defer mounting heavy Selectable Text nodes while flinging so scrolling never drops frames
  val showTextOverlay by
    produceState(initialValue = !isScrolling && !isScrubbing, key1 = isScrolling, key2 = isScrubbing) {
      if (isScrolling || isScrubbing) {
        value = false
      } else {
        delay(55L)
        value = true
      }
    }

  // Auto-hide fast scroll scrubber shortly after scrolling/dragging stops
  val showScrubber by
    produceState(initialValue = false, key1 = isScrolling, key2 = isScrubbing, key3 = totalPages) {
      if (totalPages < 4) {
        value = false
      } else if (isScrolling || isScrubbing) {
        value = true
      } else {
        delay(1300L)
        value = false
      }
    }

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxSize()
        .background(if (nightInvert) Color(0xFF0E1014) else MaterialTheme.colorScheme.surfaceVariant),
  ) {
    val viewportWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
    val viewportHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
    val renderWidthPx = remember(viewportWidthPx) { (viewportWidthPx * 1.15f).roundToInt().coerceIn(720, 1440) }

    // Pre-measure nearby page aspect ratios & render neighboring pages only when idle
    LaunchedEffect(currentPage, isScrolling, isScrubbing, helper, renderWidthPx) {
      if (isScrolling || isScrubbing) return@LaunchedEffect
      delay(90)
      if (!isScrollingState.value && !isScrubbing) {
        helper.warmupPageMetrics(currentPage, radius = 8)
        helper.prefetchPages(currentPage, renderWidthPx)
      }
    }

    fun animateZoomTo(target: Float, focalX: Float? = null) {
      val clamped = target.coerceIn(1f, 3.5f)
      zoomJob?.cancel()
      panFlingJob?.cancel()
      val startScale = zoomScale
      val startPanX = panOffsetX
      val startPanY = panOffsetY
      val targetPanX =
        if (clamped <= 1.02f) {
          0f
        } else if (focalX != null && startScale <= 1.05f) {
          val maxX = viewportWidthPx * (clamped - 1f) * 0.5f
          ((viewportWidthPx * 0.5f - focalX) * (clamped - 1f)).coerceIn(-maxX, maxX)
        } else {
          val maxX = viewportWidthPx * (clamped - 1f) * 0.5f
          startPanX.coerceIn(-maxX, maxX)
        }
      val targetPanY =
        if (clamped <= 1.02f) {
          0f
        } else {
          val maxY = viewportHeightPx * (clamped - 1f) * 0.5f
          startPanY.coerceIn(-maxY, maxY)
        }

      zoomJob =
        scope.launch {
          Animatable(0f).animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
          ) {
            val t = value
            zoomScale = startScale + (clamped - startScale) * t
            panOffsetX = startPanX + (targetPanX - startPanX) * t
            panOffsetY = startPanY + (targetPanY - startPanY) * t
          }
        }
    }

    // Gesture container: 1x tap dismisses selection, double-tap zooms, pinch & zoomed-pan w/ fling
    Box(
      modifier =
        Modifier.fillMaxSize()
          .pointerInput(Unit) {
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
              val wasActiveOnDown = textToolbar.status == TextToolbarStatus.Shown
              val startPos = down.position
              val downTime = System.currentTimeMillis()
              var moved = false
              var multiTouch = false
              while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.size > 1) multiTouch = true
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if ((change.position - startPos).getDistance() > viewConfiguration.touchSlop) {
                  moved = true
                }
                if (!change.pressed) break
              }
              if (
                wasActiveOnDown &&
                  !moved &&
                  !multiTouch &&
                  (System.currentTimeMillis() - downTime) < 280L
              ) {
                suppressDoubleTapUntil = System.currentTimeMillis() + 380L
                clearActiveTextSelection()
              }
            }
          }
          .pointerInput(Unit) {
            detectTapGestures(
              onDoubleTap = { tapOffset ->
                if (System.currentTimeMillis() < suppressDoubleTapUntil) return@detectTapGestures
                if (textToolbar.status == TextToolbarStatus.Shown) {
                  clearActiveTextSelection()
                  return@detectTapGestures
                }
                if (zoomScale > 1.15f) {
                  animateZoomTo(1f)
                } else {
                  animateZoomTo(2.15f, focalX = tapOffset.x)
                }
              },
            )
          }
          .pointerInput(viewportWidthPx, viewportHeightPx) {
            val decay = exponentialDecay<Float>(frictionMultiplier = 1.8f)
            awaitEachGesture {
              awaitFirstDown(requireUnconsumed = false)
              panFlingJob?.cancel()
              val velocityTracker = VelocityTracker()
              var wasZoomedPan = false

              do {
                val event = awaitPointerEvent()
                val pressedChanges = event.changes.filter { it.pressed }
                val pressedCount = pressedChanges.size
                val curZoom = zoomScale

                // At 100% zoom with 1 finger, let LazyColumn handle scrolling natively at zero cost
                if (pressedCount < 2 && curZoom <= 1.02f) continue

                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || (curZoom > 1.02f && pan != Offset.Zero)) {
                  zoomJob?.cancel()
                  val nextScale = (curZoom * zoom).coerceIn(1f, 3.5f)
                  zoomScale = nextScale
                  if (nextScale > 1.02f) {
                    wasZoomedPan = true
                    pressedChanges.firstOrNull()?.let { ch ->
                      velocityTracker.addPosition(ch.uptimeMillis, ch.position)
                    }
                    val maxX = viewportWidthPx * (nextScale - 1f) * 0.5f
                    val maxY = viewportHeightPx * (nextScale - 1f) * 0.5f

                    panOffsetX = (panOffsetX + pan.x).coerceIn(-maxX, maxX)

                    // Pan Y within zoomed viewport edges, then overflow seamlessly to LazyColumn scroll
                    val rawNextY = panOffsetY + pan.y
                    val clampedY = rawNextY.coerceIn(-maxY, maxY)
                    val overflowY = rawNextY - clampedY
                    panOffsetY = clampedY
                    if (abs(overflowY) > 0.25f) {
                      listState.dispatchRawDelta(-overflowY / nextScale)
                    }
                  } else {
                    wasZoomedPan = false
                    panOffsetX = 0f
                    panOffsetY = 0f
                  }
                  event.changes.forEach { it.consume() }
                }
              } while (event.changes.any { it.pressed })

              if (wasZoomedPan && zoomScale > 1.02f) {
                val vel = velocityTracker.calculateVelocity()
                val vx = vel.x
                if (abs(vx) > 180f) {
                  val startX = panOffsetX
                  val maxX = viewportWidthPx * (zoomScale - 1f) * 0.5f
                  panFlingJob =
                    scope.launch {
                      Animatable(startX).animateDecay(
                        initialVelocity = vx,
                        animationSpec = decay,
                      ) {
                        val clamped = value.coerceIn(-maxX, maxX)
                        panOffsetX = clamped
                        if (clamped != value) {
                          panFlingJob?.cancel()
                        }
                      }
                    }
                }
              }
            }
          },
    ) {
      LazyColumn(
        state = listState,
        userScrollEnabled = !isScrubbing,
        modifier =
          Modifier.fillMaxSize()
            .graphicsLayer {
              val s = zoomScale
              scaleX = s
              scaleY = s
              translationX = panOffsetX
              translationY = panOffsetY
            },
        contentPadding =
          PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = if (activeHit != null) 76.dp else 12.dp,
            bottom = 96.dp,
          ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        items(
          count = helper.pageCount,
          key = { it },
          contentType = { "pdf_page" },
        ) { pageIndex ->
          PdfPageCard(
            helper = helper,
            pageIndex = pageIndex,
            renderWidthPx = renderWidthPx,
            isScrollingState = isScrollingState,
            isScrubbingState = isScrubbingState,
            showTextOverlay = showTextOverlay || textToolbar.status == TextToolbarStatus.Shown,
            sharedTextMeasurer = sharedTextMeasurer,
            selectionResetKey = selectionResetEpoch,
            nightInvert = nightInvert,
            isHighlightedPage = activeHit?.pageIndex == pageIndex,
            pageSearchHits = hitsByPage[pageIndex].orEmpty(),
            activeHit = activeHit?.takeIf { it.pageIndex == pageIndex },
            onNoSelectableText = {
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onCopyFeedback(context.getString(R.string.pdf_scan_no_text))
            },
          )
        }
      }
    }

    // Draggable fast-scroll page scrubber for quick navigation in large PDFs
    if (totalPages >= 4) {
      AnimatedVisibility(
        visible = showScrubber,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(260)),
        modifier =
          Modifier.align(Alignment.CenterEnd)
            .padding(top = 64.dp, bottom = 92.dp),
      ) {
        PdfFastScrollScrubber(
          currentPage = currentPage,
          totalPages = totalPages,
          isScrubbing = isScrubbing,
          onScrubbingChanged = { active -> isScrubbing = active },
          onScrubToPage = { targetPage ->
            scope.launch { listState.scrollToItem(targetPage) }
          },
        )
      }
    }

    // Active PDF search snippet card at top
    AnimatedVisibility(
      visible = activeHit != null,
      modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
      activeHit?.let { hit ->
        Surface(
          modifier =
            Modifier.widthIn(max = 720.dp)
              .fillMaxWidth()
              .clickable { scope.launch { listState.animateScrollToItem(hit.pageIndex) } },
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
          shadowElevation = 4.dp,
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
            ) {
              Text(
                text = stringResource(R.string.pdf_match_page_snippet, hit.pageIndex + 1),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              )
            }
            Text(
              text = highlightSnippet(hit.snippet, trimmedQuery),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }

    // Floating bottom control bar
    Surface(
      modifier =
        Modifier.align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(bottom = 16.dp, start = 12.dp, end = 12.dp),
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 6.dp,
      shadowElevation = 8.dp,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Surface(
          modifier =
            Modifier.clip(RoundedCornerShape(18.dp))
              .clickable { showJumpDialog = true }
              .testTag("pdf_page_pill"),
          color = MaterialTheme.colorScheme.primaryContainer,
        ) {
          Text(
            text = "${currentPage + 1}/${helper.pageCount}",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
          )
        }

        IconButton(
          onClick = { animateZoomTo(zoomScale - 0.35f) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("pdf_zoom_out"),
        ) {
          Icon(
            imageVector = Icons.Rounded.ZoomOut,
            contentDescription = stringResource(R.string.action_zoom_out),
          )
        }

        TextButton(
          onClick = { animateZoomTo(1f) },
          modifier = Modifier.testTag("pdf_zoom_reset"),
        ) {
          Text(
            text = "${(zoomScale * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
          )
        }

        IconButton(
          onClick = { animateZoomTo(zoomScale + 0.35f) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("pdf_zoom_in"),
        ) {
          Icon(
            imageVector = Icons.Rounded.ZoomIn,
            contentDescription = stringResource(R.string.action_zoom_in),
          )
        }

        IconButton(
          onClick = {
            scope.launch {
              val pageText = helper.extractPageText(currentPage)
              if (pageText.isBlank()) {
                onCopyFeedback(context.getString(R.string.pdf_scan_no_text))
              } else {
                copyTextToClipboard(context, document.fileName, pageText)
                onCopyFeedback(context.getString(R.string.copied_page_text, currentPage + 1))
              }
            }
          },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("pdf_copy_page_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = stringResource(R.string.action_copy_text),
          )
        }

        IconButton(
          onClick = { nightInvert = !nightInvert },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("pdf_night_toggle"),
        ) {
          Icon(
            imageVector = if (nightInvert) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            contentDescription = stringResource(R.string.action_toggle_dark_page),
          )
        }
      }
    }
  }

  if (showJumpDialog) {
    var pageInput by remember { mutableStateOf((currentPage + 1).toString()) }
    AlertDialog(
      onDismissRequest = { showJumpDialog = false },
      title = { Text(stringResource(R.string.pdf_page_label, currentPage + 1, helper.pageCount)) },
      text = {
        OutlinedTextField(
          value = pageInput,
          onValueChange = { input -> pageInput = input.filter { it.isDigit() } },
          label = { Text("Nomor halaman (1 - ${helper.pageCount})") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
      },
      dismissButton = {
        TextButton(onClick = { showJumpDialog = false }) {
          Text(stringResource(R.string.dialog_cancel))
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            val target = (pageInput.toIntOrNull() ?: 1).coerceIn(1, totalPages) - 1
            showJumpDialog = false
            scope.launch { listState.scrollToItem(target) }
          }
        ) {
          Text("Buka")
        }
      },
    )
  }
}

@Composable
private fun PdfFastScrollScrubber(
  currentPage: Int,
  totalPages: Int,
  isScrubbing: Boolean,
  onScrubbingChanged: (Boolean) -> Unit,
  onScrubToPage: (Int) -> Unit,
) {
  val density = LocalDensity.current
  val thumbHeightDp = 44.dp
  val maxIdx = (totalPages - 1).coerceAtLeast(1)

  BoxWithConstraints(
    modifier = Modifier.fillMaxHeight().width(120.dp),
    contentAlignment = Alignment.TopEnd,
  ) {
    val trackHeightPx = with(density) { (maxHeight - thumbHeightDp).coerceAtLeast(1.dp).toPx() }
    var dragFraction by remember { mutableFloatStateOf(currentPage.toFloat() / maxIdx) }
    val activeFraction =
      if (isScrubbing) {
        dragFraction
      } else {
        (currentPage.toFloat() / maxIdx).coerceIn(0f, 1f)
      }
    val thumbOffsetPx = (activeFraction * trackHeightPx).roundToInt()

    // Touch track on right edge (48dp touch width)
    Box(
      modifier =
        Modifier.fillMaxHeight()
          .width(48.dp)
          .align(Alignment.CenterEnd)
          .testTag("pdf_fast_scrubber")
          .pointerInput(totalPages, trackHeightPx) {
            detectVerticalDragGestures(
              onDragStart = { offset ->
                onScrubbingChanged(true)
                val f = (offset.y / trackHeightPx).coerceIn(0f, 1f)
                dragFraction = f
                onScrubToPage((f * maxIdx).roundToInt().coerceIn(0, maxIdx))
              },
              onVerticalDrag = { change, _ ->
                change.consume()
                val f = (change.position.y / trackHeightPx).coerceIn(0f, 1f)
                dragFraction = f
                val nextPage = (f * maxIdx).roundToInt().coerceIn(0, maxIdx)
                onScrubToPage(nextPage)
              },
              onDragEnd = { onScrubbingChanged(false) },
              onDragCancel = { onScrubbingChanged(false) },
            )
          },
    ) {
      // Visual thumb
      Surface(
        modifier =
          Modifier.align(Alignment.TopEnd)
            .padding(end = 6.dp)
            .offset { IntOffset(0, thumbOffsetPx) }
            .width(if (isScrubbing) 8.dp else 6.dp)
            .height(thumbHeightDp),
        shape = RoundedCornerShape(4.dp),
        color =
          if (isScrubbing) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
          },
      ) {}
    }

    if (isScrubbing) {
      val displayPage = (dragFraction * maxIdx).roundToInt().coerceIn(0, maxIdx) + 1
      Surface(
        modifier =
          Modifier.align(Alignment.TopEnd)
            .padding(end = 26.dp)
            .offset { IntOffset(0, (thumbOffsetPx + 6).coerceAtLeast(0)) },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        shadowElevation = 4.dp,
      ) {
        Text(
          text = "$displayPage / $totalPages",
          style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.inverseOnSurface,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
      }
    }
  }
}

@Composable
private fun PdfPageCard(
  helper: PdfRendererHelper,
  pageIndex: Int,
  renderWidthPx: Int,
  isScrollingState: State<Boolean>,
  isScrubbingState: State<Boolean>,
  showTextOverlay: Boolean,
  sharedTextMeasurer: TextMeasurer,
  selectionResetKey: Int,
  nightInvert: Boolean,
  isHighlightedPage: Boolean,
  pageSearchHits: List<PdfSearchHit>,
  activeHit: PdfSearchHit?,
  onNoSelectableText: () -> Unit,
) {
  val cachedBitmap = remember(pageIndex, helper) { helper.getCachedPageBitmap(pageIndex) }
  val bitmapState by
    produceState<Bitmap?>(initialValue = cachedBitmap, key1 = pageIndex, key2 = helper, key3 = renderWidthPx) {
      if (value != null && !value!!.isRecycled) return@produceState
      if (isScrubbingState.value) {
        snapshotFlow { isScrubbingState.value }.first { !it }
      }
      if (isScrollingState.value) {
        delay(28L)
      }
      value = helper.renderPage(pageIndex, renderWidthPx)
    }

  val cachedWords = remember(pageIndex, helper) { helper.getCachedPageWords(pageIndex) }
  val wordsState by
    produceState<List<PdfTextWord>?>(initialValue = cachedWords, key1 = pageIndex, key2 = helper) {
      if (value != null) return@produceState
      snapshotFlow { isScrollingState.value || isScrubbingState.value }.first { !it }
      delay(65L)
      snapshotFlow { isScrollingState.value || isScrubbingState.value }.first { !it }
      value = helper.extractPageWords(pageIndex)
    }

  val bmp = bitmapState?.takeUnless { it.isRecycled }
  val placeholderRatio = remember(pageIndex, helper) { helper.getEstimatedAspectRatio(pageIndex) }

  Surface(
    modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth(),
    shape = RoundedCornerShape(8.dp),
    color = if (nightInvert) Color(0xFF121417) else Color.White,
    border =
      BorderStroke(
        width = if (isHighlightedPage) 2.dp else 0.75.dp,
        color = if (isHighlightedPage) Color(0xFFFF9100) else Color(0x1F000000),
      ),
  ) {
    if (bmp == null) {
      Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(placeholderRatio),
        contentAlignment = Alignment.Center,
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Halaman ${pageIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      val ratio = (bmp.width.toFloat() / bmp.height.coerceAtLeast(1)).coerceIn(0.4f, 2.5f)
      val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
      PdfSelectionContainer(
        words = wordsState,
        onNoSelectableText = onNoSelectableText,
        selectionResetKey = selectionResetKey,
        isOverlayActive = showTextOverlay,
        textMeasurer = sharedTextMeasurer,
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
      ) {
        Image(
          bitmap = imageBitmap,
          contentDescription = "Halaman PDF ${pageIndex + 1}",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Fit,
          colorFilter = if (nightInvert) InvertColorFilter else null,
        )

        if (pageSearchHits.isNotEmpty()) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            for (hit in pageSearchHits) {
              val isCurrent = hit == activeHit
              val fillColor = if (isCurrent) ActiveSearchMatchFill else SearchMatchFill
              for (rect in hit.rects) {
                val left = rect.left * w
                val top = rect.top * h
                val rw = ((rect.right - rect.left) * w).coerceAtLeast(4f)
                val rh = ((rect.bottom - rect.top) * h).coerceAtLeast(6f)
                drawRoundRect(
                  color = fillColor,
                  topLeft = Offset(left, top),
                  size = Size(rw, rh),
                  cornerRadius = CornerRadius(4f, 4f),
                )
                if (isCurrent) {
                  drawRoundRect(
                    color = ActiveSearchMatchStroke,
                    topLeft = Offset(left, top),
                    size = Size(rw, rh),
                    cornerRadius = CornerRadius(4f, 4f),
                    style = Stroke(width = 2.dp.toPx()),
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

private fun highlightSnippet(snippet: String, query: String): AnnotatedString {
  if (query.isEmpty()) return AnnotatedString(snippet)
  return buildAnnotatedString {
    append(snippet)
    var idx = snippet.indexOf(query, ignoreCase = true)
    while (idx >= 0) {
      addStyle(
        style =
          SpanStyle(
            background = Color(0xFFFF9100),
            color = Color.White,
            fontWeight = FontWeight.Bold,
          ),
        start = idx,
        end = idx + query.length,
      )
      idx = snippet.indexOf(query, startIndex = idx + query.length, ignoreCase = true)
    }
  }
}

internal fun copyTextToClipboard(context: Context, label: String, text: String) {
  val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
  cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
