package com.example.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.parser.XlsxParser
import com.example.domain.model.ParsedDocument
import com.example.domain.model.SpreadsheetCell
import com.example.ui.theme.ExcelEmerald
import com.example.ui.theme.JetBrainsMonoFontFamily

private data class ExcelMatchLocation(
  val sheetIndex: Int,
  val cell: SpreadsheetCell,
)

@Composable
fun ExcelViewerContent(
  document: ParsedDocument.Spreadsheet,
  initialSheetIndex: Int,
  searchQuery: String,
  activeMatchIndex: Int,
  onSearchProgress: (SearchProgress) -> Unit,
  onSheetChanged: (Int) -> Unit,
  onCopyFeedback: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val density = LocalDensity.current
  val safeInitial = initialSheetIndex.coerceIn(0, (document.sheets.size - 1).coerceAtLeast(0))
  var activeSheetIdx by remember(document.file.absolutePath) { mutableIntStateOf(safeInitial) }
  var selectedCell by remember(activeSheetIdx) { mutableStateOf<SpreadsheetCell?>(null) }

  val sheet = document.sheets.getOrNull(activeSheetIdx)
  val horizontalScroll = rememberScrollState()
  val verticalListState = rememberLazyListState()

  LaunchedEffect(activeSheetIdx) { onSheetChanged(activeSheetIdx) }

  val trimmedQuery = searchQuery.trim()
  val allMatches =
    remember(document.sheets, trimmedQuery) {
      if (trimmedQuery.isEmpty()) {
        emptyList()
      } else {
        buildList {
          document.sheets.forEachIndexed { sIdx, s ->
            s.rows.forEach { row ->
              row.forEach { cell ->
                if (cell.displayValue.contains(trimmedQuery, ignoreCase = true)) {
                  add(ExcelMatchLocation(sIdx, cell))
                }
              }
            }
          }
        }
      }
    }

  LaunchedEffect(allMatches, trimmedQuery) {
    onSearchProgress(SearchProgress(totalMatches = allMatches.size, isSearching = false))
  }

  val activeMatch =
    if (allMatches.isNotEmpty()) {
      allMatches[activeMatchIndex.coerceIn(0, allMatches.lastIndex)]
    } else {
      null
    }

  val cellWidthDp = 128.dp
  LaunchedEffect(activeMatch) {
    if (activeMatch != null) {
      if (activeSheetIdx != activeMatch.sheetIndex) {
        activeSheetIdx = activeMatch.sheetIndex
      }
      selectedCell = activeMatch.cell
      verticalListState.animateScrollToItem(activeMatch.cell.rowIndex)
      val targetXPx = with(density) { (cellWidthDp * activeMatch.cell.colIndex).toPx().toInt() }
      horizontalScroll.animateScrollTo((targetXPx - 120).coerceAtLeast(0))
    }
  }

  Column(
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    // Cell Inspector Bar with SelectionContainer + Copy Cell + Copy Sheet
    Surface(
      modifier = Modifier.fillMaxWidth(),
      color = MaterialTheme.colorScheme.surface,
      border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = ExcelEmerald.copy(alpha = 0.14f),
        ) {
          Text(
            text = selectedCell?.reference ?: "fx",
            fontFamily = JetBrainsMonoFontFamily,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = ExcelEmerald,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
        SelectionContainer(modifier = Modifier.weight(1f)) {
          Text(
            text =
              selectedCell?.displayValue?.ifEmpty { "(Kosong)" }
                ?: stringResource(R.string.cell_inspector_empty),
            style = MaterialTheme.typography.bodyMedium,
            color =
              if (selectedCell != null) MaterialTheme.colorScheme.onSurface
              else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
          )
        }
        val activeCell = selectedCell
        if (activeCell != null && activeCell.displayValue.isNotEmpty()) {
          IconButton(
            onClick = {
              copyTextToClipboard(context, activeCell.reference, activeCell.displayValue)
              onCopyFeedback(context.getString(R.string.copied_cell, activeCell.reference))
            },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("excel_copy_cell_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.ContentCopy,
              contentDescription = stringResource(R.string.action_copy_cell),
              modifier = Modifier.size(20.dp),
            )
          }
        }
        if (sheet != null && sheet.rows.isNotEmpty()) {
          IconButton(
            onClick = {
              val tsv =
                sheet.rows.joinToString("\n") { row ->
                  row.joinToString("\t") { it.displayValue }
                }
              copyTextToClipboard(context, sheet.name, tsv)
              onCopyFeedback(context.getString(R.string.copied_sheet, sheet.name))
            },
            modifier = Modifier.minimumInteractiveComponentSize().testTag("excel_copy_sheet_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.SelectAll,
              contentDescription = stringResource(R.string.action_copy_sheet),
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }

    if (sheet == null || sheet.rows.isEmpty() || sheet.columnCount == 0) {
      Box(
        modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = stringResource(R.string.xlsx_empty_sheet),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      val rowHeaderWidth = 48.dp
      val headerBg = MaterialTheme.colorScheme.surfaceVariant
      val gridLine = MaterialTheme.colorScheme.outline
      val zebraBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)

      // Sticky Column Letter Header (A, B, C...)
      Row(
        modifier = Modifier.fillMaxWidth().background(headerBg),
      ) {
        Box(
          modifier =
            Modifier.width(rowHeaderWidth)
              .height(34.dp)
              .border(0.5.dp, gridLine),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "#",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Row(
          modifier = Modifier.weight(1f).horizontalScroll(horizontalScroll),
        ) {
          for (colIdx in 0 until sheet.columnCount) {
            Box(
              modifier =
                Modifier.width(cellWidthDp)
                  .height(34.dp)
                  .border(0.5.dp, gridLine),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = XlsxParser.columnIndexToName(colIdx),
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      HorizontalDivider(color = gridLine, thickness = 1.dp)

      // Virtualized Data Rows wrapped in SelectionContainer so long-pressing a cell triggers native text selection
      SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyColumn(
          state = verticalListState,
          modifier = Modifier.fillMaxSize(),
        ) {
          itemsIndexed(items = sheet.rows, key = { idx, _ -> "${activeSheetIdx}_row_$idx" }) { rowIdx, rowCells ->
            val rowBg = if (rowIdx % 2 == 1) zebraBg else MaterialTheme.colorScheme.surface
            Row(
              modifier = Modifier.fillMaxWidth().background(rowBg),
            ) {
              // Sticky Row Number
              Box(
                modifier =
                  Modifier.width(rowHeaderWidth)
                    .height(42.dp)
                    .background(headerBg)
                    .border(0.5.dp, gridLine),
                contentAlignment = Alignment.Center,
              ) {
                Text(
                  text = "${rowIdx + 1}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }

              // Horizontally scrolling cells (tap to inspect, long-press enters native text selection)
              Row(
                modifier = Modifier.weight(1f).horizontalScroll(horizontalScroll),
              ) {
                rowCells.forEach { cell ->
                  val isSelected = selectedCell?.reference == cell.reference
                  val isActiveMatch =
                    activeMatch?.sheetIndex == activeSheetIdx &&
                      activeMatch.cell.reference == cell.reference
                  val isMatch =
                    trimmedQuery.isNotEmpty() &&
                      cell.displayValue.contains(trimmedQuery, ignoreCase = true)

                  val bg =
                    when {
                      isActiveMatch -> Color(0xFFFFB74D).copy(alpha = 0.65f)
                      isSelected -> ExcelEmerald.copy(alpha = 0.16f)
                      isMatch -> Color(0xFFFFE082).copy(alpha = 0.55f)
                      else -> Color.Transparent
                    }

                  val borderColor =
                    when {
                      isActiveMatch -> Color(0xFFFF9100)
                      isSelected -> ExcelEmerald
                      else -> gridLine
                    }

                  Box(
                    modifier =
                      Modifier.width(cellWidthDp)
                        .height(42.dp)
                        .background(bg)
                        .border(
                          width = if (isSelected || isActiveMatch) 1.5.dp else 0.5.dp,
                          color = borderColor,
                        )
                        .clickable { selectedCell = cell }
                        .padding(horizontal = 8.dp),
                    contentAlignment =
                      if (cell.isNumeric) Alignment.CenterEnd else Alignment.CenterStart,
                  ) {
                    Text(
                      text = cell.displayValue,
                      style = MaterialTheme.typography.bodySmall,
                      fontWeight = if (cell.isBold || isActiveMatch) FontWeight.SemiBold else FontWeight.Normal,
                      color =
                        if (isMatch || isActiveMatch) Color(0xFF111111)
                        else MaterialTheme.colorScheme.onSurface,
                      textAlign = if (cell.isNumeric) TextAlign.End else TextAlign.Start,
                      maxLines = 2,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.fillMaxWidth(),
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // Bottom Sheet Tabs
    if (document.sheets.isNotEmpty()) {
      Surface(
        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
      ) {
        ScrollableTabRow(
          selectedTabIndex = activeSheetIdx.coerceIn(0, document.sheets.lastIndex),
          edgePadding = 12.dp,
          containerColor = MaterialTheme.colorScheme.surface,
          contentColor = ExcelEmerald,
        ) {
          document.sheets.forEachIndexed { index, s ->
            Tab(
              selected = activeSheetIdx == index,
              onClick = { activeSheetIdx = index },
              modifier = Modifier.testTag("xlsx_sheet_tab_$index"),
              text = {
                Text(
                  text = s.name,
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = if (activeSheetIdx == index) FontWeight.Bold else FontWeight.Medium,
                  maxLines = 1,
                )
              },
            )
          }
        }
      }
    }
  }
}
