package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.data.DocumentRepository
import com.example.data.UnsupportedFormatException
import com.example.data.local.AppDatabase
import com.example.domain.model.DocumentFormat
import com.example.domain.model.ParsedDocument
import com.example.domain.model.RecentDocument
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FormatFilter {
  ALL,
  PDF,
  WORD,
  EXCEL,
}

sealed interface ViewerState {
  data object Closed : ViewerState

  data class Loading(val fileName: String) : ViewerState

  data class Ready(
    val docId: Long,
    val document: ParsedDocument,
    val initialPageOrSheet: Int,
  ) : ViewerState

  data class Error(
    val message: String,
    val fileName: String? = null,
  ) : ViewerState
}

data class HomeUiState(
  val allRecent: List<RecentDocument> = emptyList(),
  val filteredRecent: List<RecentDocument> = emptyList(),
  val selectedFilter: FormatFilter = FormatFilter.ALL,
  val searchQuery: String = "",
  val viewerState: ViewerState = ViewerState.Closed,
  val showAutoOpenGuide: Boolean = false,
  val showClearHistoryConfirm: Boolean = false,
  val showAutoOpenBanner: Boolean = true,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val repository =
    DocumentRepository(
      context = application,
      dao = AppDatabase.getInstance(application).recentDocumentDao(),
    )

  private val selectedFilter = MutableStateFlow(FormatFilter.ALL)
  private val searchQuery = MutableStateFlow("")
  private val viewerState = MutableStateFlow<ViewerState>(ViewerState.Closed)
  private val showAutoOpenGuide = MutableStateFlow(false)
  private val showClearHistoryConfirm = MutableStateFlow(false)
  private val showAutoOpenBanner = MutableStateFlow(true)

  val uiState: StateFlow<HomeUiState> =
    combine(
      repository.recentDocuments,
      selectedFilter,
      searchQuery,
      viewerState,
      showAutoOpenGuide,
      showClearHistoryConfirm,
      showAutoOpenBanner,
    ) { values ->
      @Suppress("UNCHECKED_CAST")
      val recent = values[0] as List<RecentDocument>
      val filter = values[1] as FormatFilter
      val query = values[2] as String
      val viewer = values[3] as ViewerState
      val guide = values[4] as Boolean
      val clearConfirm = values[5] as Boolean
      val banner = values[6] as Boolean

      val filtered =
        recent.filter { item ->
          val matchesFormat =
            when (filter) {
              FormatFilter.ALL -> true
              FormatFilter.PDF -> item.format == DocumentFormat.PDF
              FormatFilter.WORD ->
                item.format == DocumentFormat.DOC || item.format == DocumentFormat.DOCX
              FormatFilter.EXCEL -> item.format == DocumentFormat.XLSX
            }
          val matchesQuery =
            query.isBlank() || item.fileName.contains(query.trim(), ignoreCase = true)
          matchesFormat && matchesQuery
        }

      HomeUiState(
        allRecent = recent,
        filteredRecent = filtered,
        selectedFilter = filter,
        searchQuery = query,
        viewerState = viewer,
        showAutoOpenGuide = guide,
        showClearHistoryConfirm = clearConfirm,
        showAutoOpenBanner = banner,
      )
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5000),
      initialValue = HomeUiState(),
    )

  fun setFilter(filter: FormatFilter) {
    selectedFilter.value = filter
  }

  fun setSearchQuery(query: String) {
    searchQuery.value = query
  }

  fun openFromUri(uri: Uri) {
    val label = uri.lastPathSegment?.substringAfterLast('/') ?: "Dokumen"
    viewerState.value = ViewerState.Loading(label)
    viewModelScope.launch {
      runCatching { repository.importAndOpenUri(uri) }
        .onSuccess { (recent, parsed) ->
          viewerState.value =
            ViewerState.Ready(
              docId = recent.id,
              document = parsed,
              initialPageOrSheet = recent.lastPageOrSheetIndex,
            )
        }
        .onFailure { err ->
          val msg =
            when (err) {
              is UnsupportedFormatException ->
                getApplication<Application>().getString(R.string.error_unsupported)
              else -> err.localizedMessage ?: "Tidak dapat membuka file ini."
            }
          viewerState.value = ViewerState.Error(message = msg, fileName = label)
        }
    }
  }

  fun openRecent(recent: RecentDocument) {
    viewerState.value = ViewerState.Loading(recent.fileName)
    viewModelScope.launch {
      runCatching { repository.openCachedDocument(recent) }
        .onSuccess { parsed ->
          viewerState.value =
            ViewerState.Ready(
              docId = recent.id,
              document = parsed,
              initialPageOrSheet = recent.lastPageOrSheetIndex,
            )
        }
        .onFailure { err ->
          viewerState.value =
            ViewerState.Error(
              message = err.localizedMessage ?: "Gagal membuka dokumen.",
              fileName = recent.fileName,
            )
        }
    }
  }

  fun updateProgress(docId: Long, pageOrSheetIndex: Int) {
    viewModelScope.launch { repository.updateProgress(docId, pageOrSheetIndex) }
  }

  fun closeViewer() {
    viewerState.value = ViewerState.Closed
  }

  fun deleteRecent(doc: RecentDocument) {
    viewModelScope.launch { repository.deleteRecent(doc) }
  }

  fun requestClearHistory(show: Boolean) {
    showClearHistoryConfirm.value = show
  }

  fun confirmClearHistory() {
    showClearHistoryConfirm.value = false
    viewModelScope.launch { repository.clearAllRecent() }
  }

  fun setShowAutoOpenGuide(show: Boolean) {
    showAutoOpenGuide.value = show
  }

  fun dismissAutoOpenBanner() {
    showAutoOpenBanner.update { false }
  }
}
