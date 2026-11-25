package com.example.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.AutoAwesomeMotion
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.DocumentFormat
import com.example.domain.model.RecentDocument
import com.example.ui.FormatFilter
import com.example.ui.HomeUiState
import com.example.ui.components.FormatIconBadge
import com.example.ui.components.FormatPill
import com.example.ui.components.formatFileSize
import com.example.ui.components.formatRelativeDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
  uiState: HomeUiState,
  onPickDocument: () -> Unit,
  onOpenRecent: (RecentDocument) -> Unit,
  onDeleteRecent: (RecentDocument) -> Unit,
  onSelectFilter: (FormatFilter) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onShowAutoOpenGuide: (Boolean) -> Unit,
  onDismissBanner: () -> Unit,
  onRequestClearHistory: (Boolean) -> Unit,
  onConfirmClearHistory: () -> Unit,
) {
  Scaffold(
    modifier = Modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    containerColor = MaterialTheme.colorScheme.background,
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
          ),
        title = {
          Column {
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            )
            Text(
              text = stringResource(R.string.home_subtitle),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        },
        actions = {
          IconButton(
            onClick = { onShowAutoOpenGuide(true) },
            modifier = Modifier.testTag("guide_button").minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Outlined.HelpOutline,
              contentDescription = stringResource(R.string.action_default_app_tip),
            )
          }
          if (uiState.allRecent.isNotEmpty()) {
            IconButton(
              onClick = { onRequestClearHistory(true) },
              modifier = Modifier.testTag("clear_history_button").minimumInteractiveComponentSize(),
            ) {
              Icon(
                imageVector = Icons.Outlined.DeleteSweep,
                contentDescription = stringResource(R.string.action_clear_history),
              )
            }
          }
        },
      )
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onPickDocument,
        modifier = Modifier.navigationBarsPadding().testTag("open_file_fab"),
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        icon = {
          Icon(
            imageVector = Icons.Rounded.FolderOpen,
            contentDescription = null,
          )
        },
        text = {
          Text(
            text = stringResource(R.string.action_open_file),
            style = MaterialTheme.typography.labelLarge,
          )
        },
      )
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier.fillMaxSize().widthIn(max = 720.dp),
      ) {
        AnimatedVisibility(
          visible = uiState.showAutoOpenBanner,
          enter = fadeIn(),
          exit = fadeOut(),
        ) {
          AutoOpenTipCard(
            onLearnMore = { onShowAutoOpenGuide(true) },
            onDismiss = onDismissBanner,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
          )
        }

        if (uiState.allRecent.isNotEmpty()) {
          OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChange,
            modifier =
              Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("search_recent_input"),
            placeholder = {
              Text(
                text = stringResource(R.string.search_recent_placeholder),
                style = MaterialTheme.typography.bodyMedium,
              )
            },
            leadingIcon = {
              Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = stringResource(R.string.action_search),
              )
            },
            trailingIcon = {
              if (uiState.searchQuery.isNotEmpty()) {
                IconButton(onClick = { onSearchQueryChange("") }) {
                  Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.action_close_search),
                  )
                }
              }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
              ),
          )

          FormatFilterRow(
            selected = uiState.selectedFilter,
            allRecent = uiState.allRecent,
            onSelect = onSelectFilter,
            modifier = Modifier.padding(vertical = 4.dp),
          )
        }

        when {
          uiState.allRecent.isEmpty() -> {
            EmptyLibraryView(
              onPickDocument = onPickDocument,
              modifier = Modifier.fillMaxSize().padding(24.dp),
            )
          }
          uiState.filteredRecent.isEmpty() -> {
            EmptyFilterView(
              modifier = Modifier.fillMaxSize().padding(24.dp),
            )
          }
          else -> {
            Text(
              text = stringResource(R.string.section_recent_docs),
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
              modifier = Modifier.fillMaxSize(),
              contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              items(items = uiState.filteredRecent, key = { it.id }) { doc ->
                RecentDocumentCard(
                  doc = doc,
                  onClick = { onOpenRecent(doc) },
                  onDelete = { onDeleteRecent(doc) },
                )
              }
            }
          }
        }
      }
    }
  }

  if (uiState.showAutoOpenGuide) {
    AlertDialog(
      onDismissRequest = { onShowAutoOpenGuide(false) },
      icon = {
        Icon(
          imageVector = Icons.Rounded.TouchApp,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      },
      title = {
        Text(
          text = stringResource(R.string.dialog_default_guide_title),
          style = MaterialTheme.typography.titleLarge,
        )
      },
      text = {
        Text(
          text = stringResource(R.string.dialog_default_guide_body),
          style = MaterialTheme.typography.bodyMedium,
        )
      },
      confirmButton = {
        TextButton(onClick = { onShowAutoOpenGuide(false) }) {
          Text(stringResource(R.string.dialog_ok))
        }
      },
    )
  }

  if (uiState.showClearHistoryConfirm) {
    AlertDialog(
      onDismissRequest = { onRequestClearHistory(false) },
      title = { Text(stringResource(R.string.dialog_clear_title)) },
      text = { Text(stringResource(R.string.dialog_clear_body)) },
      dismissButton = {
        TextButton(onClick = { onRequestClearHistory(false) }) {
          Text(stringResource(R.string.dialog_cancel))
        }
      },
      confirmButton = {
        TextButton(onClick = onConfirmClearHistory) {
          Text(
            text = stringResource(R.string.dialog_confirm_clear),
            color = MaterialTheme.colorScheme.error,
          )
        }
      },
    )
  }
}

@Composable
private fun AutoOpenTipCard(
  onLearnMore: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier.fillMaxWidth().clickable(onClick = onLearnMore).testTag("auto_open_card"),
    shape = RoundedCornerShape(18.dp),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
      ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Rounded.AutoAwesomeMotion,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(22.dp),
          )
        }
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = stringResource(R.string.banner_auto_open_title),
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = stringResource(R.string.banner_auto_open_desc),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
      IconButton(
        onClick = onDismiss,
        modifier = Modifier.minimumInteractiveComponentSize(),
      ) {
        Icon(
          imageVector = Icons.Outlined.Close,
          contentDescription = stringResource(R.string.action_close_search),
          tint = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
    }
  }
}

@Composable
private fun FormatFilterRow(
  selected: FormatFilter,
  allRecent: List<RecentDocument>,
  onSelect: (FormatFilter) -> Unit,
  modifier: Modifier = Modifier,
) {
  val pdfCount = allRecent.count { it.format == DocumentFormat.PDF }
  val wordCount = allRecent.count { it.format == DocumentFormat.DOC || it.format == DocumentFormat.DOCX }
  val excelCount = allRecent.count { it.format == DocumentFormat.XLSX }

  val chips = listOf(
    Triple(FormatFilter.ALL, "${stringResource(R.string.filter_all)} (${allRecent.size})", "filter_all"),
    Triple(FormatFilter.PDF, "${stringResource(R.string.filter_pdf)} ($pdfCount)", "filter_pdf"),
    Triple(FormatFilter.WORD, "${stringResource(R.string.filter_word)} ($wordCount)", "filter_word"),
    Triple(FormatFilter.EXCEL, "${stringResource(R.string.filter_excel)} ($excelCount)", "filter_excel"),
  )

  Row(
    modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    chips.forEach { (filter, label, tag) ->
      val isSelected = selected == filter
      FilterChip(
        selected = isSelected,
        onClick = { onSelect(filter) },
        label = { Text(text = label, style = MaterialTheme.typography.labelMedium) },
        modifier = Modifier.testTag(tag),
        colors =
          FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
          ),
      )
    }
  }
}

@Composable
private fun RecentDocumentCard(
  doc: RecentDocument,
  onClick: () -> Unit,
  onDelete: () -> Unit,
) {
  Card(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .clickable(onClick = onClick)
        .testTag("recent_doc_card_${doc.id}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      FormatIconBadge(format = doc.format)
      Spacer(modifier = Modifier.width(14.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = doc.fileName,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(5.dp))
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FormatPill(format = doc.format)
          val progressLabel =
            when {
              doc.lastPageOrSheetIndex > 0 && doc.format == DocumentFormat.PDF ->
                " • Hal. ${doc.lastPageOrSheetIndex + 1}"
              doc.lastPageOrSheetIndex > 0 && doc.format == DocumentFormat.XLSX ->
                " • Sheet ${doc.lastPageOrSheetIndex + 1}"
              else -> ""
            }
          Text(
            text = "${formatFileSize(doc.fileSizeBytes)} • ${formatRelativeDate(doc.lastOpenedAt)}$progressLabel",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      IconButton(
        onClick = onDelete,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("delete_doc_${doc.id}"),
      ) {
        Icon(
          imageVector = Icons.Outlined.DeleteOutline,
          contentDescription = stringResource(R.string.action_remove_item),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun EmptyLibraryView(
  onPickDocument: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Image(
      painter = painterResource(id = R.drawable.img_empty_docs),
      contentDescription = stringResource(R.string.empty_title),
      modifier =
        Modifier.fillMaxWidth(0.78f)
          .height(190.dp)
          .clip(RoundedCornerShape(22.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant),
      contentScale = ContentScale.Crop,
    )
    Spacer(modifier = Modifier.height(22.dp))
    Text(
      text = stringResource(R.string.empty_title),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onBackground,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = stringResource(R.string.empty_description),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.widthIn(max = 420.dp),
    )
    Spacer(modifier = Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FormatPill(DocumentFormat.PDF)
      FormatPill(DocumentFormat.DOC)
      FormatPill(DocumentFormat.DOCX)
      FormatPill(DocumentFormat.XLSX)
    }
    Spacer(modifier = Modifier.height(64.dp))
  }
}

@Composable
private fun EmptyFilterView(modifier: Modifier = Modifier) {
  Column(
    modifier = modifier,
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = Icons.Outlined.Search,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(48.dp),
    )
    Spacer(modifier = Modifier.height(12.dp))
    Text(
      text = stringResource(R.string.empty_filter_title),
      style = MaterialTheme.typography.titleMedium,
      textAlign = TextAlign.Center,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = stringResource(R.string.empty_filter_description),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}
