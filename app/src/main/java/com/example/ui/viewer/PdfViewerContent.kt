package com.example.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalMaterial3Api::class)
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
  val helper = remember(document.file.absolutePath) { PdfRendererHelper(document.file) }
  DisposableEffect(helper) { onDispose { helper.close() } }

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

  var showTextSheet by remember { mutableStateOf(false) }
  var isExtractingText by remember { mutableStateOf(false) }
  var extractedPageText by remember { mutableStateOf("") }
  var extractedForPage by remember { mutableStateOf(0) }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(if (nightInvert) Color(0xFF0E1014) else MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Box(
      modifier =
        Modifier.fillMaxSize()
          .pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
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
          PdfPageCard(
            helper = helper,
            pageIndex = pageIndex,
            nightInvert = nightInvert,
            isHighlightedPage = activeHit?.pageIndex == pageIndex,
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
            val targetPage = currentPage
            extractedForPage = targetPage
            showTextSheet = true
            isExtractingText = true
            scope.launch {
              extractedPageText = helper.extractPageText(targetPage)
              isExtractingText = false
            }
          },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("pdf_copy_text_button"),
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

  if (showTextSheet) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
      onDismissRequest = { showTextSheet = false },
      sheetState = sheetState,
    ) {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = stringResource(R.string.pdf_text_sheet_title, extractedForPage + 1),
            style = MaterialTheme.typography.titleLarge,
          )
          if (!isExtractingText && extractedPageText.isNotBlank()) {
            Button(
              onClick = {
                copyTextToClipboard(context, document.fileName, extractedPageText)
                onCopyFeedback(context.getString(R.string.copied_page_text, extractedForPage + 1))
                showTextSheet = false
              },
              modifier = Modifier.testTag("pdf_copy_all_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.action_copy_all))
            }
          }
        }

        Spacer(modifier = Modifier.height(6.dp))

        if (isExtractingText) {
          Box(
            modifier = Modifier.fillMaxWidth().height(160.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
          }
        } else if (extractedPageText.isBlank()) {
          Text(
            text = stringResource(R.string.pdf_text_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 20.dp),
          )
        } else {
          Text(
            text = stringResource(R.string.pdf_text_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Spacer(modifier = Modifier.height(10.dp))
          Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 380.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          ) {
            SelectionContainer {
              Text(
                text = extractedPageText,
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                  Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp)
                    .testTag("pdf_selectable_text"),
              )
            }
          }
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
) {
  val bitmapState by
    produceState<Bitmap?>(initialValue = null, key1 = pageIndex, key2 = helper) {
      value = helper.renderPage(pageIndex)
    }

  val bmp = bitmapState
  Surface(
    modifier = Modifier.widthIn(max = 820.dp).fillMaxWidth().shadow(3.dp, RoundedCornerShape(8.dp)),
    shape = RoundedCornerShape(8.dp),
    color = if (nightInvert) Color(0xFF121417) else Color.White,
    border =
      if (isHighlightedPage) BorderStroke(2.dp, Color(0xFFFF9100)) else null,
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
      Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = "Halaman PDF ${pageIndex + 1}",
        modifier = Modifier.fillMaxWidth().aspectRatio(ratio),
        contentScale = ContentScale.Fit,
        colorFilter = if (nightInvert) InvertColorFilter else null,
      )
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
