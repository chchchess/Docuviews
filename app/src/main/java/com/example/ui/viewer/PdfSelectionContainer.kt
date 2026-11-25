package com.example.ui.viewer

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
  val minWidthPx: Float,
  val heightPx: Float,
  val textStyle: TextStyle,
)

private val PdfTextSelectionColors =
  TextSelectionColors(
    handleColor = Color(0xFF1A73E8),
    backgroundColor = Color(0x551A73E8),
  )

private val PdfOverlayLineHeightStyle =
  LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.Both,
  )

/**
 * Custom SelectionContainer wrapper tailored for rendered PDF pages.
 * Keeps the visual PDF Bitmap visible while overlaying transparent, coordinate-aligned
 * Selectable Text segments inside Jetpack Compose's SelectionContainer so users can
 * long-press, drag handles cleanly to exact start/end edges, and copy text natively.
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
        val minHitHeightPx = with(density) { 12.dp.toPx() }

        val positionedSegments =
          remember(segments, pageWidthPx, pageHeightPx, density) {
            segments.map { seg ->
              val rawW = ((seg.right - seg.left) * pageWidthPx).coerceAtLeast(8f)
              val rawH = ((seg.bottom - seg.top) * pageHeightPx).coerceAtLeast(6f)
              val renderH = maxOf(rawH, minHitHeightPx)
              val baseFontSp = with(density) { (rawH * 0.80f).coerceAtLeast(4f).toSp() }
              val lineHeightSp = with(density) { renderH.toSp() }

              val baseStyle =
                TextStyle(
                  fontFamily = FontFamily.SansSerif,
                  fontWeight = FontWeight.Normal,
                  fontSize = baseFontSp,
                  lineHeight = lineHeightSp,
                  platformStyle = PlatformTextStyle(includeFontPadding = false),
                  lineHeightStyle = PdfOverlayLineHeightStyle,
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
              if (measuredW > rawW * 0.99f) {
                val scale = ((rawW * 0.99f) / measuredW).coerceIn(0.35f, 1f)
                finalFontSp = (baseFontSp.value * scale).coerceAtLeast(3.5f).sp
                finalLetterSpacing = 0.sp
              } else if (measuredW < rawW * 0.96f && seg.text.length > 1) {
                finalFontSp = baseFontSp
                val extraPx = rawW - measuredW
                val gaps = (seg.text.length - 1).coerceAtLeast(1)
                val perCharPx = (extraPx / gaps).coerceIn(0f, rawH * 0.35f)
                val candidateLs = with(density) { perCharPx.toSp() }
                val spacedMeasure =
                  textMeasurer.measure(
                    text = seg.text,
                    style = baseStyle.copy(letterSpacing = candidateLs),
                    softWrap = false,
                    maxLines = 1,
                  )
                val spacedW = spacedMeasure.size.width.toFloat()
                finalLetterSpacing =
                  if (spacedW > rawW && spacedW > measuredW) {
                    val ratio = ((rawW - measuredW) / (spacedW - measuredW)).coerceIn(0f, 1f)
                    (candidateLs.value * ratio).sp
                  } else {
                    candidateLs
                  }
              } else {
                finalFontSp = baseFontSp
                finalLetterSpacing = 0.sp
              }

              val finalStyle =
                baseStyle.copy(
                  fontSize = finalFontSp,
                  letterSpacing = finalLetterSpacing,
                )

              val xPx =
                (seg.left * pageWidthPx)
                  .roundToInt()
                  .coerceIn(0, (pageWidthPx - 4f).roundToInt().coerceAtLeast(0))
              val centerY = ((seg.top + seg.bottom) * 0.5f) * pageHeightPx
              val yPx =
                (centerY - renderH * 0.5f)
                  .roundToInt()
                  .coerceIn(0, (pageHeightPx - renderH).roundToInt().coerceAtLeast(0))

              PositionedPdfSegment(
                text = seg.text,
                xPx = xPx,
                yPx = yPx,
                minWidthPx = rawW,
                heightPx = renderH,
                textStyle = finalStyle,
              )
            }
          }

        for (seg in positionedSegments) {
          val minWidthDp = with(density) { seg.minWidthPx.toDp() }.coerceAtLeast(4.dp)
          val heightDp = with(density) { seg.heightPx.toDp() }.coerceAtLeast(8.dp)
          Text(
            text = seg.text,
            color = Color.Transparent,
            style = seg.textStyle,
            softWrap = false,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            modifier =
              Modifier.offset { IntOffset(seg.xPx, seg.yPx) }
                .widthIn(min = minWidthDp)
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
    val sameLine = abs(curMidY - nextMidY) <= maxOf(lineH * 0.55f, 0.006f)
    val gap = next.left - curRight
    val reasonableWordGap = gap >= -lineH * 1.5f && gap <= maxOf(lineH * 1.5f, 0.018f)

    if (sameLine && reasonableWordGap) {
      val glueWithoutSpace =
        gap <= lineH * 0.08f &&
          (buffer.endsWith("-") ||
            buffer.endsWith("/") ||
            next.text.startsWith("-") ||
            next.text.startsWith(",") ||
            next.text.startsWith(".") ||
            next.text.startsWith(":") ||
            next.text.startsWith(";"))
      if (!glueWithoutSpace) buffer.append(' ')
      buffer.append(next.text)
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
