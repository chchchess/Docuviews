package com.example.ui.viewer

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.R
import com.example.domain.model.ParsedDocument
import com.example.ui.ViewerState
import com.example.ui.components.FormatPill
import kotlinx.coroutines.launch

data class SearchProgress(
  val totalMatches: Int = 0,
  val isSearching: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
  viewerState: ViewerState,
  onBack: () -> Unit,
  onPickAnotherFile: () -> Unit,
  onUpdateProgress: (docId: Long, index: Int) -> Unit,
) {
  BackHandler(onBack = onBack)

  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  var showSearchBar by remember(viewerState) { mutableStateOf(false) }
  var searchQuery by remember(viewerState) { mutableStateOf("") }
  var activeMatchIndex by remember(viewerState) { mutableIntStateOf(0) }
  var searchProgress by remember(viewerState) { mutableStateOf(SearchProgress()) }
  val searchFocusRequester = remember { FocusRequester() }

  LaunchedEffect(showSearchBar) {
    if (showSearchBar) {
      runCatching { searchFocusRequester.requestFocus() }
    }
  }

  val showCopiedMessage: (String) -> Unit = { message ->
    scope.launch {
      snackbarHostState.currentSnackbarData?.dismiss()
      snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
    }
  }

  val readyState = viewerState as? ViewerState.Ready
  val doc = readyState?.document
  val supportsTextSearch = doc != null

  val stepMatch: (Int) -> Unit = { delta ->
    val total = searchProgress.totalMatches
    if (total > 0) {
      activeMatchIndex = ((activeMatchIndex + delta) % total + total) % total
    }
  }

  Scaffold(
    modifier = Modifier.fillMaxSize(),
    contentWindowInsets = WindowInsets.safeDrawing,
    containerColor = MaterialTheme.colorScheme.background,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      Column {
        TopAppBar(
          colors =
            TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surface,
              titleContentColor = MaterialTheme.colorScheme.onSurface,
            ),
          navigationIcon = {
            IconButton(
              onClick = onBack,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("viewer_back_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_back),
              )
            }
          },
          title = {
            when (viewerState) {
              is ViewerState.Ready -> {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  FormatPill(format = viewerState.document.format)
                  Text(
                    text = viewerState.document.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
              is ViewerState.Loading -> {
                Text(
                  text = viewerState.fileName,
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              is ViewerState.Error -> {
                Text(
                  text = viewerState.fileName ?: stringResource(R.string.error_open_title),
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              ViewerState.Closed -> Unit
            }
          },
          actions = {
            if (supportsTextSearch) {
              IconButton(
                onClick = {
                  showSearchBar = !showSearchBar
                  if (!showSearchBar) {
                    searchQuery = ""
                    activeMatchIndex = 0
                    searchProgress = SearchProgress()
                  }
                },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("viewer_search_toggle"),
              ) {
                Icon(
                  imageVector = if (showSearchBar) Icons.Outlined.Close else Icons.Outlined.Search,
                  contentDescription = stringResource(R.string.action_search),
                )
              }
            }
            if (doc != null) {
              IconButton(
                onClick = { shareDocument(context, doc) },
                modifier = Modifier.minimumInteractiveComponentSize().testTag("viewer_share_button"),
              ) {
                Icon(
                  imageVector = Icons.Outlined.Share,
                  contentDescription = stringResource(R.string.action_share_file),
                )
              }
            }
          },
        )

        AnimatedVisibility(visible = showSearchBar && supportsTextSearch) {
          ViewerSearchBar(
            query = searchQuery,
            activeMatchIndex = activeMatchIndex,
            progress = searchProgress,
            focusRequester = searchFocusRequester,
            onQueryChange = { next ->
              searchQuery = next
              activeMatchIndex = 0
            },
            onPrevMatch = { stepMatch(-1) },
            onNextMatch = { stepMatch(1) },
            onClearOrClose = {
              if (searchQuery.isNotEmpty()) {
                searchQuery = ""
                activeMatchIndex = 0
                searchProgress = SearchProgress()
              } else {
                showSearchBar = false
              }
            },
          )
        }
      }
    },
  ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      when (viewerState) {
        is ViewerState.Loading -> {
          Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            CircularProgressIndicator(modifier = Modifier.size(40.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(
              text = stringResource(R.string.loading_document),
              style = MaterialTheme.typography.titleMedium,
            )
          }
        }

        is ViewerState.Error -> {
          Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Icon(
              imageVector = Icons.Outlined.ErrorOutline,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(56.dp),
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
              text = stringResource(R.string.error_open_title),
              style = MaterialTheme.typography.titleLarge,
              textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = viewerState.message,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(onClick = onPickAnotherFile, modifier = Modifier.testTag("error_retry_button")) {
              Text(stringResource(R.string.error_retry))
            }
          }
        }

        is ViewerState.Ready -> {
          when (val parsed = viewerState.document) {
            is ParsedDocument.Pdf -> {
              PdfViewerContent(
                document = parsed,
                initialPage = viewerState.initialPageOrSheet,
                searchQuery = searchQuery,
                activeMatchIndex = activeMatchIndex,
                onSearchProgress = { searchProgress = it },
                onPageChanged = { page -> onUpdateProgress(viewerState.docId, page) },
                onCopyFeedback = showCopiedMessage,
              )
            }
            is ParsedDocument.RichText -> {
              WordViewerContent(
                document = parsed,
                searchQuery = searchQuery,
                activeMatchIndex = activeMatchIndex,
                onSearchProgress = { searchProgress = it },
                onCopyFeedback = showCopiedMessage,
              )
            }
            is ParsedDocument.Spreadsheet -> {
              ExcelViewerContent(
                document = parsed,
                initialSheetIndex = viewerState.initialPageOrSheet,
                searchQuery = searchQuery,
                activeMatchIndex = activeMatchIndex,
                onSearchProgress = { searchProgress = it },
                onSheetChanged = { sheet -> onUpdateProgress(viewerState.docId, sheet) },
                onCopyFeedback = showCopiedMessage,
              )
            }
          }
        }

        ViewerState.Closed -> Unit
      }
    }
  }
}

@Composable
private fun ViewerSearchBar(
  query: String,
  activeMatchIndex: Int,
  progress: SearchProgress,
  focusRequester: FocusRequester,
  onQueryChange: (String) -> Unit,
  onPrevMatch: () -> Unit,
  onNextMatch: () -> Unit,
  onClearOrClose: () -> Unit,
) {
  Surface(
    modifier = Modifier.fillMaxWidth(),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier =
          Modifier.weight(1f)
            .focusRequester(focusRequester)
            .testTag("viewer_search_input"),
        placeholder = {
          Text(
            text = stringResource(R.string.search_in_doc_placeholder),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onNextMatch() }),
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
          ),
        trailingIcon = {
          if (query.isNotEmpty()) {
            IconButton(onClick = onClearOrClose) {
              Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.action_close_search),
              )
            }
          }
        },
      )

      if (query.isNotBlank()) {
        val statusText =
          when {
            progress.isSearching -> "…"
            progress.totalMatches == 0 -> "0/0"
            else -> "${activeMatchIndex.coerceIn(0, progress.totalMatches - 1) + 1}/${progress.totalMatches}"
          }
        Text(
          text = statusText,
          style = MaterialTheme.typography.labelSmall,
          color =
            if (!progress.isSearching && progress.totalMatches == 0) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          modifier = Modifier.padding(horizontal = 4.dp).testTag("viewer_search_count"),
        )
      }

      IconButton(
        onClick = onPrevMatch,
        enabled = progress.totalMatches > 0,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("viewer_search_prev"),
      ) {
        Icon(
          imageVector = Icons.Rounded.KeyboardArrowUp,
          contentDescription = stringResource(R.string.search_prev_match),
        )
      }

      IconButton(
        onClick = onNextMatch,
        enabled = progress.totalMatches > 0,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("viewer_search_next"),
      ) {
        Icon(
          imageVector = Icons.Rounded.KeyboardArrowDown,
          contentDescription = stringResource(R.string.search_next_match),
        )
      }
    }
  }
}

private fun shareDocument(context: Context, doc: ParsedDocument) {
  runCatching {
    val uri =
      FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        doc.file,
      )
    val shareIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = doc.format.primaryMimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
    context.startActivity(Intent.createChooser(shareIntent, doc.fileName))
  }
}
