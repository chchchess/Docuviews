package com.example.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.parser.PdfTextWord
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class PdfTextSegment(
  val text: String,
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

private data class PositionedPdfSegment(
  val text: String,
  val xPx: Int,
  val yPx: Int,
  val widthPx: Float,
  val heightPx: Float,
  val fontSize: TextUnit,
  val lineHeight: TextUnit,
  val letterSpacing: TextUnit,
)

private val PdfTextSelectionColors =
  TextSelectionColors(
    handleColor = Color(0xFF1A73E8),
    backgroundColor = Color(0x551A73E8),
  )

/**
 * Custom SelectionContainer wrapper tailored for rendered PDF pages.
 * Keeps the visual PDF Bitmap visible while overlaying transparent, coordinate-aligned
 * Selectable Text segments inside Jetpack Compose's SelectionContainer so users can
 * long-press, drag handles, and copy text natively on digital PDFs.
 */
@Composable
fun PdfSelectionContainer(
  words: List<PdfTextWord>?,
  onNoSelectableText: () -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable BoxScope.() -> Unit,
) {
  val segments = remember(words) { buildPdfTextSegments(words.orEmpty()) }

  if (segments.isEmpty()) {
    val scanTapModifier =
      if (words != null) {
        Modifier.pointerInput(words) {
          detectTapGestures(onLongPress = { onNoSelectableText() })
        }
      } else {
        Modifier
      }
    Box(
      modifier = modifier.then(scanTapModifier),
      content = content,
    )
    return
  }

  val density = LocalDensity.current
  val textMeasurer = rememberTextMeasurer()

  CompositionLocalProvider(LocalTextSelectionColors provides PdfTextSelectionColors) {
    SelectionContainer(
      modifier = modifier.testTag("pdf_selection_container"),
    ) {
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        content()

        val pageWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val pageHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)

        val positionedSegments =
          remember(segments, pageWidthPx, pageHeightPx, density) {
            segments.map { seg ->
              val targetW = ((seg.right - seg.left) * pageWidthPx).coerceAtLeast(12f)
              val targetH = ((seg.bottom - seg.top) * pageHeightPx).coerceAtLeast(10f)
              val baseFontSp = with(density) { (targetH * 0.78f).coerceAtLeast(6f).toSp() }
              val lineHeightSp = with(density) { targetH.toSp() }

              val baseStyle =
                TextStyle(
                  fontSize = baseFontSp,
                  lineHeight = lineHeightSp,
                  platformStyle = PlatformTextStyle(includeFontPadding = false),
                  lineHeightStyle =
                    LineHeightStyle(
                      alignment = LineHeightStyle.Alignment.Center,
                      trim = LineHeightStyle.Trim.Both,
                    ),
                )

              val measured =
                textMeasurer.measure(
                  text = seg.text,
                  style = baseStyle,
                  softWrap = false,
                  maxLines = 1,
                )
              val measuredW = measured.size.width.toFloat().coerceAtLeast(1f)

              val finalFontSp: TextUnit
              val finalLetterSpacing: TextUnit
              if (measuredW > targetW * 1.02f) {
                val scale = (targetW / measuredW).coerceIn(0.4f, 1f)
                finalFontSp = (baseFontSp.value * scale).sp
                finalLetterSpacing = 0.sp
              } else if (measuredW < targetW * 0.96f && seg.text.length > 1) {
                finalFontSp = baseFontSp
                val extraPx = targetW - measuredW
                val perCharPx = (extraPx / seg.text.length).coerceIn(0f, targetH * 0.45f)
                finalLetterSpacing = with(density) { perCharPx.toSp() }
              } else {
                finalFontSp = baseFontSp
                finalLetterSpacing = 0.sp
              }

              PositionedPdfSegment(
                text = seg.text,
                xPx = (seg.left * pageWidthPx).roundToInt().coerceAtLeast(0),
                yPx = (seg.top * pageHeightPx).roundToInt().coerceAtLeast(0),
                widthPx = targetW,
                heightPx = targetH,
                fontSize = finalFontSp,
                lineHeight = lineHeightSp,
                letterSpacing = finalLetterSpacing,
              )
            }
          }

        for (seg in positionedSegments) {
          val widthDp = with(density) { seg.widthPx.toDp() }.coerceAtLeast(4.dp)
          val heightDp = with(density) { seg.heightPx.toDp() }.coerceAtLeast(6.dp)
          Text(
            text = seg.text,
            color = Color.Transparent,
            fontSize = seg.fontSize,
            lineHeight = seg.lineHeight,
            letterSpacing = seg.letterSpacing,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            style =
              TextStyle(
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle =
                  LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                  ),
              ),
            modifier =
              Modifier.offset { IntOffset(seg.xPx, seg.yPx) }
                .width(widthDp)
                .height(heightDp),
          )
        }
      }
    }
  }
}

/**
 * Groups normalized PDF words into contiguous same-line segments while preserving column
 * boundaries so multi-column PDFs and tables select cleanly column-by-column.
 */
internal fun buildPdfTextSegments(words: List<PdfTextWord>): List<PdfTextSegment> {
  if (words.isEmpty()) return emptyList()

  // Sort roughly by vertical band then horizontal position to guarantee geometric reading order
  val sorted =
    words.sortedWith { a, b ->
      val aMidY = (a.top + a.bottom) * 0.5f
      val bMidY = (b.top + b.bottom) * 0.5f
      val lineH = maxOf(a.bottom - a.top, b.bottom - b.top, 0.01f)
      if (abs(aMidY - bMidY) <= lineH * 0.5f) {
        a.left.compareTo(b.left)
      } else {
        aMidY.compareTo(bMidY)
      }
    }

  val segments = mutableListOf<PdfTextSegment>()
  val buffer = StringBuilder(sorted[0].text)
  var curLeft = sorted[0].left
  var curTop = sorted[0].top
  var curRight = sorted[0].right
  var curBottom = sorted[0].bottom

  for (i in 1 until sorted.size) {
    val next = sorted[i]
    val curMidY = (curTop + curBottom) * 0.5f
    val nextMidY = (next.top + next.bottom) * 0.5f
    val lineH = maxOf(curBottom - curTop, next.bottom - next.top, 0.01f)
    val sameLine = abs(curMidY - nextMidY) <= maxOf(lineH * 0.55f, 0.008f)
    val gap = next.left - curRight
    val reasonableWordGap = gap >= -lineH * 0.5f && gap <= maxOf(lineH * 1.45f, 0.045f)

    if (sameLine && reasonableWordGap) {
      buffer.append(' ').append(next.text)
      curLeft = minOf(curLeft, next.left)
      curTop = minOf(curTop, next.top)
      curRight = maxOf(curRight, next.right)
      curBottom = maxOf(curBottom, next.bottom)
    } else {
      segments.add(
        PdfTextSegment(
          text = buffer.toString(),
          left = curLeft,
          top = curTop,
          right = curRight,
          bottom = curBottom,
        )
      )
      buffer.clear()
      buffer.append(next.text)
      curLeft = next.left
      curTop = next.top
      curRight = next.right
      curBottom = next.bottom
    }
  }

  segments.add(
    PdfTextSegment(
      text = buffer.toString(),
      left = curLeft,
      top = curTop,
      right = curRight,
      bottom = curBottom,
    )
  )

  return segments
}
