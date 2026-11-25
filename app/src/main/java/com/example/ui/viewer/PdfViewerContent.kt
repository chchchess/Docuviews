package com.example.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.SelectAll
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.parser.PdfRendererHelper
import com.example.data.parser.PdfSearchHit
import com.example.data.parser.PdfTextWord
import com.example.domain.model.ParsedDocument
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
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

private val SelectionHighlightFill = Color(0x551A73E8)
private val SelectionHighlightStroke = Color(0x991A73E8)
private val SelectionHandleColor = Color(0xFF1A73E8)
private val SearchMatchFill = Color(0x4DFFA000)
private val ActiveSearchMatchFill = Color(0x88FF6D00)
private val ActiveSearchMatchStroke = Color(0xFFE65100)

private data class PdfSelectionState(
  val pageIndex: Int,
  val words: List<PdfTextWord>,
  val startIndex: Int,
  val endIndex: Int,
) {
  val minIndex: Int
    get() = minOf(startIndex, endIndex).coerceIn(0, words.lastIndex)

  val maxIndex: Int
    get() = maxOf(startIndex, endIndex).coerceIn(0, words.lastIndex)

  val selectedWords: List<PdfTextWord>
    get() = if (words.isEmpty()) emptyList() else words.subList(minIndex, maxIndex + 1)

  val selectedText: String
    get() = selectedWords.joinToString(" ") { it.text }
}

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
  val textToolbar = LocalTextToolbar.current
  val haptic = LocalHapticFeedback.current
  val helper = remember(document.file.absolutePath) { PdfRendererHelper(document.file) }
  DisposableEffect(helper) {
    onDispose {
      runCatching { textToolbar.hide() }
      helper.close()
    }
  }

  val totalPages = helper.pageCount.coerceAtLeast(1)
  val startIdx = initialPage.coerceIn(0, totalPages - 1)
  val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIdx)
  val scope = rememberCoroutineScope()

  val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
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
  var panOffsetX by remember { mutableFloatStateOf(0f) }
  var panOffsetY by remember { mutableFloatStateOf(0f) }
  var nightInvert by remember { mutableStateOf(false) }
  var showJumpDialog by remember { mutableStateOf(false) }

  var selection by remember(document.file.absolutePath) { mutableStateOf<PdfSelectionState?>(null) }
  var isDraggingHandle by remember { mutableStateOf(false) }
  var selectedPageCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
  var selectedPageSizePx by remember { mutableStateOf(Size.Zero) }

  BackHandler(enabled = selection != null) {
    selection = null
    runCatching { textToolbar.hide() }
  }

  val density = LocalDensity.current
  val toolbarMarginBottomPx = with(density) { 28.dp.toPx() }

  // Show or hide Android's native floating text-selection toolbar anchored to the selected words on the PDF page
  LaunchedEffect(
    selection,
    isDraggingHandle,
    listState.isScrollInProgress,
    zoomScale,
    panOffsetX,
    panOffsetY,
    selectedPageCoords,
    selectedPageSizePx,
  ) {
    val activeSel = selection
    val coords = selectedPageCoords
    if (
      activeSel == null ||
        isDraggingHandle ||
        listState.isScrollInProgress ||
        coords == null ||
        !coords.isAttached ||
        selectedPageSizePx.width <= 1f ||
        selectedPageSizePx.height <= 1f
    ) {
      runCatching { textToolbar.hide() }
      return@LaunchedEffect
    }

    val selWords = activeSel.selectedWords
    if (selWords.isEmpty()) {
      runCatching { textToolbar.hide() }
      return@LaunchedEffect
    }

    val minL = selWords.minOf { it.left } * selectedPageSizePx.width
    val minT = selWords.minOf { it.top } * selectedPageSizePx.height
    val maxR = selWords.maxOf { it.right } * selectedPageSizePx.width
    val maxB = selWords.maxOf { it.bottom } * selectedPageSizePx.height

    val topLeftRoot = coords.localToRoot(Offset(minL, minT))
    val bottomRightRoot = coords.localToRoot(Offset(maxR, maxB + toolbarMarginBottomPx))
    val menuRect =
      Rect(
        left = minOf(topLeftRoot.x, bottomRightRoot.x),
        top = minOf(topLeftRoot.y, bottomRightRoot.y),
        right = maxOf(topLeftRoot.x, bottomRightRoot.x),
        bottom = maxOf(topLeftRoot.y, bottomRightRoot.y),
      )

    val canSelectMore =
      activeSel.minIndex > 0 || activeSel.maxIndex < activeSel.words.lastIndex

    runCatching {
      textToolbar.showMenu(
        rect = menuRect,
        onCopyRequested = {
          copyTextToClipboard(context, document.fileName, activeSel.selectedText)
          onCopyFeedback(context.getString(R.string.copied_selected_text))
          selection = null
          runCatching { textToolbar.hide() }
        },
        onSelectAllRequested =
          if (canSelectMore) {
            {
              selection = activeSel.copy(startIndex = 0, endIndex = activeSel.words.lastIndex)
            }
          } else {
            null
          },
      )
    }
  }

  val hitsByPage = remember(searchHits) { searchHits.groupBy { it.pageIndex } }

  fun startSelectionOnPage(pageIndex: Int, normX: Float?, normY: Float?) {
    scope.launch {
      val words = helper.extractPageWords(pageIndex)
      if (words.isEmpty()) {
        selection = null
        runCatching { textToolbar.hide() }
        onCopyFeedback(context.getString(R.string.pdf_scan_no_text))
        return@launch
      }
      if (normX == null || normY == null) {
        selection =
          PdfSelectionState(
            pageIndex = pageIndex,
            words = words,
            startIndex = 0,
            endIndex = words.lastIndex,
          )
      } else {
        val hitIdx = findNearestWordIndex(words, normX, normY)
        selection =
          PdfSelectionState(
            pageIndex = pageIndex,
            words = words,
            startIndex = hitIdx,
            endIndex = hitIdx,
          )
      }
    }
  }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(if (nightInvert) Color(0xFF0E1014) else MaterialTheme.colorScheme.surfaceVariant),
  ) {
    // Handle multi-finger pinch zoom & pan while zoomed; single-finger scroll & long-press pass cleanly
    Box(
      modifier =
        Modifier.fillMaxSize()
          .pointerInput(Unit) {
            awaitEachGesture {
              awaitFirstDown(requireUnconsumed = false)
              do {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2 || zoomScale > 1.02f) {
                  val zoom = event.calculateZoom()
                  val pan = event.calculatePan()
                  if (
                    zoom != 1f ||
                      (zoomScale > 1.02f && pan != Offset.Zero)
                  ) {
                    val nextScale = (zoomScale * zoom).coerceIn(1f, 3.5f)
                    zoomScale = nextScale
                    if (nextScale > 1.02f) {
                      val maxX = size.width * (nextScale - 1f) * 0.5f
                      val maxY = size.height * (nextScale - 1f) * 0.25f
                      panOffsetX = (panOffsetX + pan.x).coerceIn(-maxX, maxX)
                      panOffsetY = (panOffsetY + pan.y).coerceIn(-maxY, maxY)
                    } else {
                      panOffsetX = 0f
                      panOffsetY = 0f
                    }
                    event.changes.forEach { it.consume() }
                  }
                }
              } while (event.changes.any { it.pressed })
            }
          },
    ) {
      LazyColumn(
        state = listState,
        modifier =
          Modifier.fillMaxSize()
            .graphicsLayer {
              scaleX = zoomScale
              scaleY = zoomScale
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
        items(count = helper.pageCount, key = { it }) { pageIndex ->
          val pageSelection = selection?.takeIf { it.pageIndex == pageIndex }
          val pageHits = hitsByPage[pageIndex].orEmpty()

          PdfPageCard(
            helper = helper,
            pageIndex = pageIndex,
            nightInvert = nightInvert,
            isHighlightedPage = activeHit?.pageIndex == pageIndex,
            pageSearchHits = pageHits,
            activeHit = activeHit?.takeIf { it.pageIndex == pageIndex },
            selection = pageSelection,
            onTapPage = {
              if (selection != null) {
                selection = null
                runCatching { textToolbar.hide() }
              }
            },
            onLongPressAt = { nx, ny ->
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              startSelectionOnPage(pageIndex, nx, ny)
            },
            onSelectionRangeChange = { newStart, newEnd ->
              selection?.let { cur ->
                if (cur.pageIndex == pageIndex) {
                  selection = cur.copy(startIndex = newStart, endIndex = newEnd)
                }
              }
            },
            onDragHandleStateChange = { dragging -> isDraggingHandle = dragging },
            onPagePositioned = { coords, sizePx ->
              if (selection?.pageIndex == pageIndex) {
                selectedPageCoords = coords
                selectedPageSizePx = sizePx
              }
            },
          )
        }
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
          onClick = {
            zoomScale = (zoomScale - 0.35f).coerceIn(1f, 3.5f)
            if (zoomScale <= 1.02f) {
              panOffsetX = 0f
              panOffsetY = 0f
            }
          },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("pdf_zoom_out"),
        ) {
          Icon(
            imageVector = Icons.Rounded.ZoomOut,
            contentDescription = stringResource(R.string.action_zoom_out),
          )
        }

        TextButton(
          onClick = {
            zoomScale = 1f
            panOffsetX = 0f
            panOffsetY = 0f
          },
          modifier = Modifier.testTag("pdf_zoom_reset"),
        ) {
          Text(
            text = "${(zoomScale * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
          )
        }

        IconButton(
          onClick = { zoomScale = (zoomScale + 0.35f).coerceIn(1f, 3.5f) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("pdf_zoom_in"),
        ) {
          Icon(
            imageVector = Icons.Rounded.ZoomIn,
            contentDescription = stringResource(R.string.action_zoom_in),
          )
        }

        IconButton(
          onClick = {
            if (selection?.pageIndex == currentPage) {
              selection = null
              runCatching { textToolbar.hide() }
            } else {
              startSelectionOnPage(currentPage, null, null)
            }
          },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("pdf_select_all_button"),
        ) {
          Icon(
            imageVector = Icons.Outlined.SelectAll,
            contentDescription = stringResource(R.string.action_select_page_text),
            tint =
              if (selection?.pageIndex == currentPage) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurface,
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
private fun PdfPageCard(
  helper: PdfRendererHelper,
  pageIndex: Int,
  nightInvert: Boolean,
  isHighlightedPage: Boolean,
  pageSearchHits: List<PdfSearchHit>,
  activeHit: PdfSearchHit?,
  selection: PdfSelectionState?,
  onTapPage: () -> Unit,
  onLongPressAt: (normX: Float, normY: Float) -> Unit,
  onSelectionRangeChange: (startIndex: Int, endIndex: Int) -> Unit,
  onDragHandleStateChange: (Boolean) -> Unit,
  onPagePositioned: (LayoutCoordinates, Size) -> Unit,
) {
  val bitmapState by
    produceState<Bitmap?>(initialValue = null, key1 = pageIndex, key2 = helper) {
      value = helper.renderPage(pageIndex)
    }

  val latestSelection by rememberUpdatedState(selection)
  val bmp = bitmapState

  Surface(
    modifier =
      Modifier.widthIn(max = 820.dp)
        .fillMaxWidth()
        .shadow(3.dp, RoundedCornerShape(8.dp)),
    shape = RoundedCornerShape(8.dp),
    color = if (nightInvert) Color(0xFF121417) else Color.White,
    border = if (isHighlightedPage) BorderStroke(2.dp, Color(0xFFFF9100)) else null,
  ) {
    if (bmp == null) {
      Box(
        modifier = Modifier.fillMaxWidth().aspectRatio(0.71f),
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
      val density = LocalDensity.current
      val handleRadiusPx = with(density) { 10.dp.toPx() }
      val handleHitRadiusPx = with(density) { 34.dp.toPx() }

      BoxWithConstraints(
        modifier =
          Modifier.fillMaxWidth()
            .aspectRatio(ratio)
            .onGloballyPositioned { coords ->
              onPagePositioned(
                coords,
                Size(coords.size.width.toFloat(), coords.size.height.toFloat()),
              )
            }
            // Handle drags on the teardrop handles in non-moving page coordinates
            .pointerInput(pageIndex) {
              awaitEachGesture {
                val down =
                  awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val sel = latestSelection ?: return@awaitEachGesture
                if (sel.pageIndex != pageIndex || sel.words.isEmpty()) return@awaitEachGesture

                val w = size.width.toFloat().coerceAtLeast(1f)
                val h = size.height.toFloat().coerceAtLeast(1f)
                val minW = sel.words[sel.minIndex]
                val maxW = sel.words[sel.maxIndex]

                val startHandleCenter =
                  Offset(minW.left * w, (minW.bottom * h + handleRadiusPx * 1.4f).coerceAtMost(h))
                val endHandleCenter =
                  Offset(maxW.right * w, (maxW.bottom * h + handleRadiusPx * 1.4f).coerceAtMost(h))

                val distStart =
                  hypot(down.position.x - startHandleCenter.x, down.position.y - startHandleCenter.y)
                val distEnd =
                  hypot(down.position.x - endHandleCenter.x, down.position.y - endHandleCenter.y)

                if (minOf(distStart, distEnd) > handleHitRadiusPx) return@awaitEachGesture

                val draggingStart = distStart <= distEnd
                val fixedIndex = if (draggingStart) sel.maxIndex else sel.minIndex
                down.consume()
                onDragHandleStateChange(true)

                try {
                  while (true) {
                    val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                    val change =
                      event.changes.firstOrNull { it.id == down.id }
                        ?: event.changes.firstOrNull()
                        ?: break
                    if (!change.pressed) {
                      change.consume()
                      break
                    }
                    change.consume()
                    val nx = (change.position.x / w).coerceIn(0f, 1f)
                    val ny =
                      ((change.position.y - handleRadiusPx * 1.4f) / h).coerceIn(0f, 1f)
                    val targetIdx = findNearestWordIndex(sel.words, nx, ny)
                    if (draggingStart) {
                      onSelectionRangeChange(targetIdx, fixedIndex)
                    } else {
                      onSelectionRangeChange(fixedIndex, targetIdx)
                    }
                  }
                } finally {
                  onDragHandleStateChange(false)
                }
              }
            }
            // Long-press anywhere on a PDF page to enter direct on-page selection; tap to clear
            .pointerInput(pageIndex) {
              detectTapGestures(
                onTap = { onTapPage() },
                onLongPress = { offset ->
                  val w = size.width.toFloat().coerceAtLeast(1f)
                  val h = size.height.toFloat().coerceAtLeast(1f)
                  onLongPressAt((offset.x / w).coerceIn(0f, 1f), (offset.y / h).coerceIn(0f, 1f))
                },
              )
            },
      ) {
        Image(
          bitmap = bmp.asImageBitmap(),
          contentDescription = "Halaman PDF ${pageIndex + 1}",
          modifier = Modifier.fillMaxSize(),
          contentScale = ContentScale.Fit,
          colorFilter = if (nightInvert) InvertColorFilter else null,
        )

        // Overlay canvas: search highlights + native-style blue selection highlight & handles
        if (pageSearchHits.isNotEmpty() || selection != null) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // 1. Search highlight boxes directly on the PDF page
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

            // 2. Direct in-place text selection highlights & teardrop handles
            val activeSel = selection
            if (activeSel != null && activeSel.selectedWords.isNotEmpty()) {
              val mergedLineRects = mergeLineHighlights(activeSel.selectedWords)
              for (box in mergedLineRects) {
                val padX = 2.dp.toPx()
                val padY = 1.dp.toPx()
                val left = (box.left * w - padX).coerceAtLeast(0f)
                val top = (box.top * h - padY).coerceAtLeast(0f)
                val rw = ((box.right - box.left) * w + padX * 2f).coerceAtLeast(8f)
                val rh = ((box.bottom - box.top) * h + padY * 2f).coerceAtLeast(10f)
                drawRoundRect(
                  color = SelectionHighlightFill,
                  topLeft = Offset(left, top),
                  size = Size(rw, rh),
                  cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                )
                drawRoundRect(
                  color = SelectionHighlightStroke,
                  topLeft = Offset(left, top),
                  size = Size(rw, rh),
                  cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                  style = Stroke(width = 1.dp.toPx()),
                )
              }

              val firstWord = activeSel.selectedWords.first()
              val lastWord = activeSel.selectedWords.last()
              val r = 9.dp.toPx()

              // Left (start) teardrop handle anchored at bottom-left of first selected word
              drawSelectionHandle(
                anchor = Offset(firstWord.left * w, firstWord.bottom * h),
                radius = r,
                pointLeft = true,
              )

              // Right (end) teardrop handle anchored at bottom-right of last selected word
              drawSelectionHandle(
                anchor = Offset(lastWord.right * w, lastWord.bottom * h),
                radius = r,
                pointLeft = false,
              )
            }
          }
        }
      }
    }
  }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionHandle(
  anchor: Offset,
  radius: Float,
  pointLeft: Boolean,
) {
  val center =
    Offset(
      x = if (pointLeft) (anchor.x - radius * 0.55f).coerceIn(radius, size.width - radius) else (anchor.x + radius * 0.55f).coerceIn(radius, size.width - radius),
      y = (anchor.y + radius * 1.25f).coerceAtMost(size.height - radius),
    )

  // Draw small teardrop pointer connecting anchor -> handle circle
  val stem =
    Path().apply {
      moveTo(anchor.x.coerceIn(2f, size.width - 2f), anchor.y.coerceIn(2f, size.height - radius))
      lineTo(center.x - radius * 0.6f, center.y)
      lineTo(center.x + radius * 0.6f, center.y)
      close()
    }
  drawPath(stem, color = SelectionHandleColor)
  drawCircle(color = Color.White, radius = radius + 1.5.dp.toPx(), center = center)
  drawCircle(color = SelectionHandleColor, radius = radius, center = center)
}

private fun mergeLineHighlights(words: List<PdfTextWord>): List<PdfTextWord> {
  if (words.isEmpty()) return emptyList()
  val merged = mutableListOf<PdfTextWord>()
  var current = words[0]
  for (i in 1 until words.size) {
    val next = words[i]
    val curMidY = (current.top + current.bottom) * 0.5f
    val nextMidY = (next.top + next.bottom) * 0.5f
    val lineH = maxOf(current.bottom - current.top, next.bottom - next.top)
    val sameLine = abs(curMidY - nextMidY) <= maxOf(lineH * 0.6f, 0.01f) && next.left >= current.left - 0.02f
    if (sameLine) {
      current =
        current.copy(
          left = minOf(current.left, next.left),
          top = minOf(current.top, next.top),
          right = maxOf(current.right, next.right),
          bottom = maxOf(current.bottom, next.bottom),
        )
    } else {
      merged.add(current)
      current = next
    }
  }
  merged.add(current)
  return merged
}

private fun findNearestWordIndex(words: List<PdfTextWord>, nx: Float, ny: Float): Int {
  if (words.isEmpty()) return 0
  // Prefer direct hit inside word box (with slight vertical tolerance)
  words.forEachIndexed { idx, w ->
    if (nx in (w.left - 0.01f)..(w.right + 0.01f) && ny in (w.top - 0.008f)..(w.bottom + 0.008f)) {
      return idx
    }
  }
  // Otherwise find nearest word weighting vertical distance slightly stronger than horizontal
  var bestIdx = 0
  var bestDist = Float.MAX_VALUE
  words.forEachIndexed { idx, w ->
    val cx = nx.coerceIn(w.left, w.right)
    val cy = ny.coerceIn(w.top, w.bottom)
    val dx = nx - cx
    val dy = (ny - cy) * 1.8f
    val d = dx * dx + dy * dy
    if (d < bestDist) {
      bestDist = d
      bestIdx = idx
    }
  }
  return bestIdx
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
