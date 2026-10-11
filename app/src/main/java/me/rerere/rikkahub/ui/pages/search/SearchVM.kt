package me.rerere.rikkahub.ui.pages.search

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.Brand
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.fts.MessageSearchResult
import me.rerere.rikkahub.data.db.fts.MessageSearchSort
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.vector.ConversationIndexCoordinator
import me.rerere.rikkahub.data.vector.ConversationSearchResult
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import kotlin.uuid.Uuid

private const val SORT_ORDER_PREF_KEY = "search_page_sort_order"

enum class MessageSearchScope {
    CURRENT_ASSISTANT,
    ALL_ASSISTANTS,
}

private data class SearchRequest(
    val query: String,
    val sort: MessageSearchSort,
    val scope: MessageSearchScope,
    val assistantId: Uuid?,
    val debounce: Boolean,
)

/**
 * The search page, over one of two backends — which one is the whole point of the split.
 *
 * **Moxw** runs on the history vector index, the same one `conversation_search` hands the model.
 * The keyword (FTS) path is gone along with the rest of the app's keyword fallbacks, so a query is
 * matched by meaning and an empty result means "no passage is close" rather than "no word matched".
 * Two things the keyword index gave away for free then have to be done explicitly, and both are
 * visible here:
 *
 *  - **Ordering.** Vectors produce one order — relevance — so the sort selector is gone (see
 *    [SearchPage]). A date ordering was the keyword index's `ORDER BY`, and reproducing it would
 *    mean reading every hit's conversation just to re-sort a list that is already the right answer
 *    to the question that was asked.
 *  - **Scope.** The index is deliberately not per-assistant (see `ConversationSearchRules.SOURCE`),
 *    so "this assistant only" is a filter over the resolved hits, not a key in the index. The filter
 *    can therefore throw most of a page away, which is why a scoped search asks for more candidates
 *    than it shows — [SCOPED_CANDIDATES].
 *
 * **pure** keeps the keyword index it has always searched: [ftsResults], the sort selector, and the
 * rebuild progress the FTS rebuild reports. Nothing on that path touches the embedder.
 */
class SearchVM(
    private val context: Application,
    private val conversationRepo: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val conversationIndex: ConversationIndexCoordinator,
) : ViewModel() {
    private val searchRequests = Channel<SearchRequest>(Channel.CONFLATED)
    private var currentAssistantId: Uuid? = null

    var searchQuery by mutableStateOf("")
        private set
    var searchScope by mutableStateOf(MessageSearchScope.CURRENT_ASSISTANT)
        private set

    /** Moxw results — history passages ranked by meaning. */
    var results by mutableStateOf<List<ConversationSearchResult>>(emptyList())
        private set

    /** pure results — the keyword index's hits, the ones this page has always shown. */
    var ftsResults by mutableStateOf<List<MessageSearchResult>>(emptyList())
        private set

    /** pure only: the keyword index yields an order, and the user picks which. */
    var sortOrder by mutableStateOf(
        runCatching {
            MessageSearchSort.valueOf(
                context.readStringPreference(SORT_ORDER_PREF_KEY, MessageSearchSort.RELEVANCE.name)!!
            )
        }.getOrDefault(MessageSearchSort.RELEVANCE)
    )
        private set

    var isLoading by mutableStateOf(false)
        private set
    var isRebuilding by mutableStateOf(false)
        private set

    /** pure only: how far the FTS rebuild has got. */
    var rebuildProgress by mutableStateOf(0 to 0)
        private set

    /**
     * Set when the last search could not run at all because nothing can be embedded. Distinct from
     * "no results": one is fixed by installing a model, the other by asking differently.
     */
    var needsEmbeddingModel by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            searchRequests.receiveAsFlow().collectLatest { request -> performSearch(request) }
        }
        viewModelScope.launch {
            settingsStore.settingsFlow
                .map { it.getCurrentAssistant().id }
                .distinctUntilChanged()
                .collect { assistantId ->
                    currentAssistantId = assistantId
                    if (searchScope == MessageSearchScope.CURRENT_ASSISTANT) {
                        search()
                    }
                }
        }
        if (Brand.isMoxw) {
            // The rebuild runs in the index's own scope, so the button's state is read back from it
            // rather than tracked locally: a sweep started anywhere else still lights the progress
            // bar. Opening the page is another chance to catch up — the same debounced sweep the
            // chat does, so arriving here right after a conversation costs nothing, while arriving
            // here first is meaningful because this page reads the same index the model does.
            viewModelScope.launch {
                conversationIndex.status.collect { isRebuilding = it.running }
            }
            conversationIndex.requestSync()
        }
    }

    fun onQueryChange(query: String) {
        searchQuery = query
        requestSearch(debounce = true)
    }

    fun onScopeChange(scope: MessageSearchScope) {
        if (searchScope == scope) return
        searchScope = scope
        search()
    }

    /** pure only — the keyword index is the only backend that has an order to choose. */
    fun onSortChange(sort: MessageSearchSort) {
        if (sortOrder == sort) return
        sortOrder = sort
        context.writeStringPreference(SORT_ORDER_PREF_KEY, sort.name)
        search()
    }

    fun search() {
        requestSearch()
    }

    /**
     * Rebuild the index, now, rather than through the debounce a conversation open uses.
     *
     * Moxw rebuilds the history vector index (the work is the same increment; the difference is
     * only that the user asked for it). pure rebuilds the FTS index, which reports its own progress
     * instead of a running/not flag.
     */
    fun rebuildIndex() {
        if (Brand.isMoxw) {
            conversationIndex.requestSync(minIntervalMs = 0L)
            return
        }
        viewModelScope.launch {
            isRebuilding = true
            rebuildProgress = 0 to 0
            try {
                conversationRepo.rebuildAllIndexes { current, total ->
                    rebuildProgress = current to total
                }
            } finally {
                isRebuilding = false
            }
        }
    }

    private fun requestSearch(debounce: Boolean = false) {
        val assistantId = when (searchScope) {
            MessageSearchScope.CURRENT_ASSISTANT -> currentAssistantId
            MessageSearchScope.ALL_ASSISTANTS -> null
        }
        searchRequests.trySend(
            SearchRequest(
                query = searchQuery,
                sort = sortOrder,
                scope = searchScope,
                assistantId = assistantId,
                debounce = debounce,
            )
        )
    }

    private suspend fun performSearch(request: SearchRequest) {
        results = emptyList()
        ftsResults = emptyList()
        needsEmbeddingModel = false
        if (request.query.isBlank() ||
            (request.scope == MessageSearchScope.CURRENT_ASSISTANT && request.assistantId == null)
        ) {
            return
        }
        isLoading = true
        try {
            if (request.debounce) delay(300L)
            if (Brand.isMoxw) {
                val scoped = request.assistantId
                val response = conversationIndex.search(
                    query = request.query,
                    limit = if (scoped == null) RESULT_LIMIT else SCOPED_CANDIDATES,
                )
                if (!response.available) {
                    needsEmbeddingModel = true
                    return
                }
                results = response.results
                    .filter { scoped == null || it.assistantId == scoped.toString() }
                    .take(RESULT_LIMIT)
            } else {
                ftsResults = conversationRepo.searchMessages(request.query, request.sort, request.assistantId)
            }
        } finally {
            isLoading = false
        }
    }

    companion object {
        /** What the page shows. */
        private const val RESULT_LIMIT = 20

        /**
         * What a scoped search asks for. The assistant filter runs after the search, over hits the
         * index resolved, so a page's worth of them can be spent on other assistants entirely. Three
         * times the page is a guess at the worst case, not a measured number: the cost of being
         * wrong is one conversation read per extra hit, all of which are cached by id.
         */
        private const val SCOPED_CANDIDATES = 60
    }
}
