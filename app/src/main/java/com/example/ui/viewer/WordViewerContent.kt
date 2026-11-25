package com.example.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.domain.model.DocBlock
import com.example.domain.model.ParsedDocument
import com.example.domain.model.StyledSpan
import kotlin.math.roundToInt

private data class WordMatchLocation(
  val blockIndex: Int,
  val occurrenceInBlock: Int,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WordViewerContent(
  document: ParsedDocument.RichText,
  searchQuery: String,
  activeMatchIndex: Int,
  onSearchProgress: (SearchProgress) -> Unit,
  onCopyFeedback: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  var textScale by remember { mutableFloatStateOf(1f) }
  var darkPaper by remember { mutableStateOf(false) }

  val trimmedQuery = searchQuery.trim()
  val matches =
    remember(document.blocks, trimmedQuery) {
      if (trimmedQuery.isEmpty()) emptyList() else findWordMatches(document.blocks, trimmedQuery)
    }

  LaunchedEffect(matches, trimmedQuery) {
    onSearchProgress(SearchProgress(totalMatches = matches.size, isSearching = false))
  }

  val activeMatch =
    if (matches.isNotEmpty()) matches[activeMatchIndex.coerceIn(0, matches.lastIndex)] else null

  val paperColor = if (darkPaper) Color(0xFF151920) else Color(0xFFFFFFFF)
  val inkColor = if (darkPaper) Color(0xFFE7ECF3) else Color(0xFF1A1E26)
  val borderColor = if (darkPaper) Color(0xFF2B3240) else Color(0xFFE2DCD3)
  val backdropColor = if (darkPaper) Color(0xFF0D1015) else MaterialTheme.colorScheme.surfaceVariant

  Box(modifier = modifier.fillMaxSize().background(backdropColor)) {
    if (document.blocks.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
          text = stringResource(R.string.docx_empty_content),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else if (document.blocks.size <= 30) {
      val requesters =
        remember(document.blocks) {
          List(document.blocks.size) { BringIntoViewRequester() }
        }

      LaunchedEffect(activeMatch) {
        if (activeMatch != null && activeMatch.blockIndex in requesters.indices) {
          requesters[activeMatch.blockIndex].bringIntoView()
        }
      }

      SelectionContainer {
        Column(
          modifier =
            Modifier.fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          if (document.isLegacyNotice) {
            LegacyNoticeBanner()
          }
          Surface(
            modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = paperColor,
            border = BorderStroke(1.dp, borderColor),
          ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
              document.blocks.forEachIndexed { blockIdx, block ->
                val activeOccurrenceInBlock =
                  if (activeMatch?.blockIndex == blockIdx) activeMatch.occurrenceInBlock else -1
                Box(
                  modifier =
                    Modifier.fillMaxWidth()
                      .padding(vertical = 4.dp)
                      .bringIntoViewRequester(requesters[blockIdx]),
                ) {
                  DocBlockView(
                    block = block,
                    textScale = textScale,
                    inkColor = inkColor,
                    borderColor = borderColor,
                    darkPaper = darkPaper,
                    searchQuery = trimmedQuery,
                    activeOccurrenceInBlock = activeOccurrenceInBlock,
                  )
                }
              }
            }
          }
        }
      }
    } else {
      val listState = rememberLazyListState()
      LaunchedEffect(activeMatch) {
        if (activeMatch != null) {
          val offset = if (document.isLegacyNotice) 1 else 0
          listState.animateScrollToItem(activeMatch.blockIndex + offset)
        }
      }

      SelectionContainer {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
        ) {
          if (document.isLegacyNotice) {
            item(key = "legacy_notice") { LegacyNoticeBanner() }
          }
          itemsIndexed(
            items = document.blocks,
            key = { idx, _ -> "block_$idx" },
            contentType = { _, block -> block::class },
          ) { blockIdx, block ->
            val activeOccurrenceInBlock =
              if (activeMatch?.blockIndex == blockIdx) activeMatch.occurrenceInBlock else -1
            Surface(
              modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(),
              color = paperColor,
            ) {
              Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp)) {
                DocBlockView(
                  block = block,
                  textScale = textScale,
                  inkColor = inkColor,
                  borderColor = borderColor,
                  darkPaper = darkPaper,
                  searchQuery = trimmedQuery,
                  activeOccurrenceInBlock = activeOccurrenceInBlock,
                )
              }
            }
          }
        }
      }
    }

    // Floating reading comfort & copy controls
    Surface(
      modifier =
        Modifier.align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(bottom = 16.dp),
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
      tonalElevation = 6.dp,
      shadowElevation = 8.dp,
    ) {
      Row(
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        IconButton(
          onClick = { textScale = (textScale - 0.1f).coerceIn(0.8f, 1.5f) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("word_text_smaller"),
        ) {
          Icon(imageVector = Icons.Rounded.TextDecrease, contentDescription = "Perkecil teks")
        }
        Text(
          text = "${(textScale * 100).roundToInt()}%",
          style = MaterialTheme.typography.labelSmall,
          modifier = Modifier.padding(horizontal = 4.dp),
        )
        IconButton(
          onClick = { textScale = (textScale + 0.1f).coerceIn(0.8f, 1.5f) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("word_text_larger"),
        ) {
          Icon(imageVector = Icons.Rounded.TextIncrease, contentDescription = "Perbesar teks")
        }
        if (document.blocks.isNotEmpty()) {
          IconButton(
            onClick = {
              val allText = extractAllPlainText(document.blocks)
              if (allText.isNotBlank()) {
                copyTextToClipboard(context, document.fileName, allText)
                onCopyFeedback(context.getString(R.string.copied_all_doc))
              }
            },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("word_copy_all_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.ContentCopy,
              contentDescription = stringResource(R.string.action_copy_all),
            )
          }
        }
        IconButton(
          onClick = { darkPaper = !darkPaper },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("word_dark_toggle"),
        ) {
          Icon(
            imageVector = if (darkPaper) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            contentDescription = stringResource(R.string.action_toggle_dark_page),
          )
        }
      }
    }
  }
}

private fun findWordMatches(blocks: List<DocBlock>, query: String): List<WordMatchLocation> {
  val list = mutableListOf<WordMatchLocation>()
  blocks.forEachIndexed { blockIdx, block ->
    var occ = 0
    when (block) {
      is DocBlock.Heading -> {
        val full = block.spans.joinToString("") { it.text }
        var idx = full.indexOf(query, ignoreCase = true)
        while (idx >= 0) {
          list.add(WordMatchLocation(blockIdx, occ++))
          idx = full.indexOf(query, idx + query.length, ignoreCase = true)
        }
      }
      is DocBlock.Paragraph -> {
        val full = block.spans.joinToString("") { it.text }
        var idx = full.indexOf(query, ignoreCase = true)
        while (idx >= 0) {
          list.add(WordMatchLocation(blockIdx, occ++))
          idx = full.indexOf(query, idx + query.length, ignoreCase = true)
        }
      }
      is DocBlock.Table -> {
        block.rows.forEach { row ->
          row.forEach { cell ->
            var idx = cell.indexOf(query, ignoreCase = true)
            while (idx >= 0) {
              list.add(WordMatchLocation(blockIdx, occ++))
              idx = cell.indexOf(query, idx + query.length, ignoreCase = true)
            }
          }
        }
      }
      is DocBlock.ImageBlock, DocBlock.PageBreak -> Unit
    }
  }
  return list
}

@Composable
private fun LegacyNoticeBanner() {
  Surface(
    modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(bottom = 10.dp),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.Info,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(18.dp),
      )
      Text(
        text = stringResource(R.string.doc_legacy_notice),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }
  }
}

private fun extractAllPlainText(blocks: List<DocBlock>): String {
  val sb = StringBuilder()
  for (block in blocks) {
    when (block) {
      is DocBlock.Heading -> {
        if (sb.isNotEmpty()) sb.append("\n\n")
        sb.append(block.spans.joinToString("") { it.text })
      }
      is DocBlock.Paragraph -> {
        if (sb.isNotEmpty()) sb.append("\n")
        if (block.isListItem) sb.append("• ")
        sb.append(block.spans.joinToString("") { it.text })
      }
      is DocBlock.Table -> {
        if (sb.isNotEmpty()) sb.append("\n")
        block.rows.forEach { row ->
          sb.append(row.joinToString("\t")).append("\n")
        }
      }
      is DocBlock.ImageBlock, DocBlock.PageBreak -> Unit
    }
  }
  return sb.toString().trim()
}

@Composable
private fun DocBlockView(
  block: DocBlock,
  textScale: Float,
  inkColor: Color,
  borderColor: Color,
  darkPaper: Boolean,
  searchQuery: String,
  activeOccurrenceInBlock: Int,
) {
  when (block) {
    is DocBlock.Heading -> {
      val baseSp =
        when (block.level) {
          1 -> 23f
          2 -> 19.5f
          3 -> 17f
          else -> 15.5f
        }
      val headingAnnotated =
        remember(block.spans, textScale, inkColor, darkPaper, searchQuery, activeOccurrenceInBlock) {
          buildStyledAnnotatedString(
            spans = block.spans,
            textScale = textScale,
            defaultInk = inkColor,
            darkPaper = darkPaper,
            searchQuery = searchQuery,
            activeOccurrenceInBlock = activeOccurrenceInBlock,
            forceBold = true,
          )
        }
      Text(
        text = headingAnnotated,
        fontSize = (baseSp * textScale).sp,
        lineHeight = (baseSp * 1.35f * textScale).sp,
        textAlign = block.alignment,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
      )
    }

    is DocBlock.Paragraph -> {
      val annotated =
        remember(block.spans, textScale, inkColor, darkPaper, searchQuery, activeOccurrenceInBlock) {
          buildStyledAnnotatedString(
            spans = block.spans,
            textScale = textScale,
            defaultInk = inkColor,
            darkPaper = darkPaper,
            searchQuery = searchQuery,
            activeOccurrenceInBlock = activeOccurrenceInBlock,
          )
        }
      if (block.isListItem) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
          Text(
            text = block.listMarker ?: "•",
            color = inkColor,
            fontSize = (15f * textScale).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(18.dp),
          )
          Text(
            text = annotated,
            fontSize = (14.5f * textScale).sp,
            lineHeight = (21.5f * textScale).sp,
            textAlign = block.alignment,
            modifier = Modifier.weight(1f),
          )
        }
      } else {
        Text(
          text = annotated,
          fontSize = (14.5f * textScale).sp,
          lineHeight = (21.5f * textScale).sp,
          textAlign = block.alignment,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }

    is DocBlock.Table -> {
      val headerBg = if (darkPaper) Color(0xFF1F2733) else Color(0xFFF3EFEA)
      var runningCellOccurrence = 0
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .padding(vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
      ) {
        Column(modifier = Modifier.border(1.dp, borderColor, RoundedCornerShape(6.dp))) {
          block.rows.forEachIndexed { rowIndex, row ->
            Row(
              modifier =
                Modifier.background(if (rowIndex == 0) headerBg else Color.Transparent),
            ) {
              row.forEach { cellText ->
                val cellHighlight =
                  highlightCellQuery(
                    text = cellText,
                    query = searchQuery,
                    defaultColor = inkColor,
                    startOccurrence = runningCellOccurrence,
                    activeOccurrenceInBlock = activeOccurrenceInBlock,
                  )
                runningCellOccurrence = cellHighlight.second
                Box(
                  modifier =
                    Modifier.widthIn(min = 100.dp, max = 240.dp)
                      .border(0.5.dp, borderColor)
                      .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                  Text(
                    text = cellHighlight.first,
                    fontSize = (13f * textScale).sp,
                    fontWeight = if (rowIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = inkColor,
                  )
                }
              }
            }
          }
        }
      }
    }

    is DocBlock.ImageBlock -> {
      Image(
        bitmap = block.bitmap.asImageBitmap(),
        contentDescription = "Gambar dokumen",
        modifier =
          Modifier.fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .aspectRatio(block.aspectRatio),
        contentScale = ContentScale.Fit,
      )
    }

    DocBlock.PageBreak -> {
      HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = borderColor,
      )
    }
  }
}

private fun buildStyledAnnotatedString(
  spans: List<StyledSpan>,
  textScale: Float,
  defaultInk: Color,
  darkPaper: Boolean,
  searchQuery: String,
  activeOccurrenceInBlock: Int,
  forceBold: Boolean = false,
): AnnotatedString {
  val query = searchQuery.trim()
  return buildAnnotatedString {
    val fullTextBuilder = StringBuilder()
    for (span in spans) {
      val start = length
      append(span.text)
      fullTextBuilder.append(span.text)
      val end = length

      val resolvedColor =
        when {
          span.colorRgb != null && !darkPaper -> Color(span.colorRgb)
          else -> defaultInk
        }

      val decorations = mutableListOf<TextDecoration>()
      if (span.underline) decorations.add(TextDecoration.Underline)
      if (span.strikethrough) decorations.add(TextDecoration.LineThrough)

      addStyle(
        style =
          SpanStyle(
            color = resolvedColor,
            fontWeight = if (span.bold || forceBold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (span.italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration =
              if (decorations.isEmpty()) TextDecoration.None else TextDecoration.combine(decorations),
            fontSize = span.fontSizeSp?.let { (it * textScale).sp } ?: androidx.compose.ui.unit.TextUnit.Unspecified,
          ),
        start = start,
        end = end,
      )
    }

    if (query.isNotEmpty()) {
      val fullText = fullTextBuilder.toString()
      var occ = 0
      var idx = fullText.indexOf(query, ignoreCase = true)
      while (idx >= 0) {
        val isActive = occ == activeOccurrenceInBlock
        addStyle(
          style =
            SpanStyle(
              background = if (isActive) Color(0xFFFF9100) else Color(0xFFFFE57F),
              color = if (isActive) Color.White else Color(0xFF111111),
              fontWeight = if (isActive) FontWeight.Bold else null,
            ),
          start = idx,
          end = idx + query.length,
        )
        occ++
        idx = fullText.indexOf(query, startIndex = idx + query.length, ignoreCase = true)
      }
    }
  }
}

private fun highlightCellQuery(
  text: String,
  query: String,
  defaultColor: Color,
  startOccurrence: Int,
  activeOccurrenceInBlock: Int,
): Pair<AnnotatedString, Int> {
  if (query.isEmpty()) return AnnotatedString(text) to startOccurrence
  var occ = startOccurrence
  val annotated =
    buildAnnotatedString {
      append(text)
      addStyle(SpanStyle(color = defaultColor), 0, text.length)
      var idx = text.indexOf(query, ignoreCase = true)
      while (idx >= 0) {
        val isActive = occ == activeOccurrenceInBlock
        addStyle(
          style =
            SpanStyle(
              background = if (isActive) Color(0xFFFF9100) else Color(0xFFFFE57F),
              color = if (isActive) Color.White else Color(0xFF111111),
              fontWeight = if (isActive) FontWeight.Bold else null,
            ),
          start = idx,
          end = idx + query.length,
        )
        occ++
        idx = text.indexOf(query, startIndex = idx + query.length, ignoreCase = true)
      }
    }
  return annotated to occ
}
