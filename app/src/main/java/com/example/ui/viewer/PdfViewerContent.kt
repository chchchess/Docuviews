package com.example.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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

  val currentPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }
  LaunchedEffect(currentPage) { onPageChanged(currentPage) }

  // Pre-render neighboring pages quietly in background so vertical scrolling feels instant
  LaunchedEffect(currentPage, helper) {
    delay(60)
    helper.prefetchPages(currentPage)
  }

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

  val zoomScaleAnim = remember { Animatable(1f) }
  var panOffsetX by remember { mutableFloatStateOf(0f) }
  var panOffsetY by remember { mutableFloatStateOf(0f) }
  var nightInvert by remember { mutableStateOf(false) }
  var showJumpDialog by remember { mutableStateOf(false) }

  fun animateZoomTo(target: Float) {
    val clamped = target.coerceIn(1f, 3.5f)
    scope.launch {
      if (clamped <= 1.02f) {
        panOffsetX = 0f
        panOffsetY = 0f
      }
      zoomScaleAnim.animateTo(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
      )
    }
  }

  val hitsByPage = remember(searchHits) { searchHits.groupBy { it.pageIndex } }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(if (nightInvert) Color(0xFF0E1014) else MaterialTheme.colorScheme.surfaceVariant),
  ) {
    // Fast-path zoom/pan: zero math overhead on single-finger 1x vertical flings + smooth double-tap zoom
    Box(
      modifier =
        Modifier.fillMaxSize()
          .pointerInput(Unit) {
            detectTapGestures(
              onDoubleTap = {
                if (zoomScaleAnim.value > 1.15f) {
                  animateZoomTo(1f)
                } else {
                  animateZoomTo(2f)
                }
              },
            )
          }
          .pointerInput(Unit) {
            awaitEachGesture {
              awaitFirstDown(requireUnconsumed = false)
              do {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                val curZoom = zoomScaleAnim.value
                if (pressedCount < 2 && curZoom <= 1.02f) continue

                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || (curZoom > 1.02f && pan != Offset.Zero)) {
                  val nextScale = (curZoom * zoom).coerceIn(1f, 3.5f)
                  scope.launch { zoomScaleAnim.snapTo(nextScale) }
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
              } while (event.changes.any { it.pressed })
            }
          },
    ) {
      LazyColumn(
        state = listState,
        modifier =
          Modifier.fillMaxSize()
            .graphicsLayer {
              val s = zoomScaleAnim.value
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
          onClick = { animateZoomTo(zoomScaleAnim.value - 0.35f) },
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
            text = "${(zoomScaleAnim.value * 100).roundToInt()}%",
            style = MaterialTheme.typography.labelSmall,
          )
        }

        IconButton(
          onClick = { animateZoomTo(zoomScaleAnim.value + 0.35f) },
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
private fun PdfPageCard(
  helper: PdfRendererHelper,
  pageIndex: Int,
  nightInvert: Boolean,
  isHighlightedPage: Boolean,
  pageSearchHits: List<PdfSearchHit>,
  activeHit: PdfSearchHit?,
  onNoSelectableText: () -> Unit,
) {
  val bitmapState by
    produceState<Bitmap?>(initialValue = null, key1 = pageIndex, key2 = helper) {
      value = helper.renderPage(pageIndex)
    }

  val wordsState by
    produceState<List<PdfTextWord>?>(initialValue = null, key1 = pageIndex, key2 = helper) {
      delay(150)
      value = helper.extractPageWords(pageIndex)
    }

  val bmp = bitmapState
  val placeholderRatio = remember(pageIndex, helper) { helper.getCachedAspectRatio(pageIndex) ?: 0.71f }

  Surface(
    modifier =
      Modifier.widthIn(max = 820.dp)
        .fillMaxWidth()
        .shadow(2.dp, RoundedCornerShape(8.dp)),
    shape = RoundedCornerShape(8.dp),
    color = if (nightInvert) Color(0xFF121417) else Color.White,
    border = if (isHighlightedPage) BorderStroke(2.dp, Color(0xFFFF9100)) else null,
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
      PdfSelectionContainer(
        words = wordsState,
        onNoSelectableText = onNoSelectableText,
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
      ) {
        Image(
          bitmap = bmp.asImageBitmap(),
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
