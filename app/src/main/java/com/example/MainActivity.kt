package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.model.DocumentFormat
import com.example.ui.MainViewModel
import com.example.ui.ViewerState
import com.example.ui.home.HomeScreen
import com.example.ui.theme.DocuViewTheme
import com.example.ui.viewer.ViewerScreen

class MainActivity : ComponentActivity() {

  private val viewModel: MainViewModel by viewModels()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    if (savedInstanceState == null) {
      handleIncomingIntent(intent)
    }

    setContent {
      DocuViewTheme {
        DocuViewRoot(viewModel = viewModel)
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIncomingIntent(intent)
  }

  private fun handleIncomingIntent(intent: Intent?) {
    if (intent == null) return
    val uri: Uri? =
      when (intent.action) {
        Intent.ACTION_VIEW -> intent.data ?: intent.clipData?.getItemAt(0)?.uri
        Intent.ACTION_SEND ->
          IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.getItemAt(0)?.uri
        else -> null
      }
    if (uri != null) {
      viewModel.openFromUri(uri)
    }
  }
}

@Composable
private fun DocuViewRoot(viewModel: MainViewModel) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()

  val openDocumentLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
      if (uri != null) {
        viewModel.openFromUri(uri)
      }
    }

  val launchPicker = {
    openDocumentLauncher.launch(DocumentFormat.allSupportedMimeTypes)
  }

  Crossfade(
    targetState = uiState.viewerState !is ViewerState.Closed,
    label = "screen_transition",
  ) { isViewerOpen ->
    if (isViewerOpen) {
      ViewerScreen(
        viewerState = uiState.viewerState,
        onBack = viewModel::closeViewer,
        onPickAnotherFile = launchPicker,
        onUpdateProgress = viewModel::updateProgress,
      )
    } else {
      HomeScreen(
        uiState = uiState,
        onPickDocument = launchPicker,
        onOpenRecent = viewModel::openRecent,
        onDeleteRecent = viewModel::deleteRecent,
        onSelectFilter = viewModel::setFilter,
        onSearchQueryChange = viewModel::setSearchQuery,
        onShowAutoOpenGuide = viewModel::setShowAutoOpenGuide,
        onDismissBanner = viewModel::dismissAutoOpenBanner,
        onRequestClearHistory = viewModel::requestClearHistory,
        onConfirmClearHistory = viewModel::confirmClearHistory,
      )
    }
  }
}
