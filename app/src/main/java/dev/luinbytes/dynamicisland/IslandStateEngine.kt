package dev.luinbytes.dynamicisland

import androidx.compose.runtime.mutableStateOf
import java.util.Collections
import java.util.LinkedHashMap

/** The broad origin of an Island source. A publisher may own multiple source IDs. */
internal enum class IslandSourceKind {
    TIMER,
    MEDIA,
    NOTIFICATION,
    LIVE_ACTIVITY,
    APP_OWNED,
    SYSTEM,
    OTHER,
}

/** Source lifecycle is independent of whether the source currently has a visible Island slot. */
internal enum class IslandLifecycle {
    PENDING,
    ACTIVE,
    STALE,
    ENDED,
    DISMISSED,
    UNAVAILABLE,
}

internal enum class IslandPresentation {
    IDLE,
    COMPACT,
    MULTIPLE,
    EXPANDED,
}

internal enum class IslandSelectionOrigin {
    AUTOMATIC,
    USER,
}

/**
 * Current in-memory source state. `id` is the stable identity of one activity/session, not its
 * publisher. Publishers must increment `revision` for every accepted state change and use a new
 * ID for a new activity after a terminal lifecycle.
 *
 * Display strings are held only in process memory by [IslandStateEngine]; they are not persisted.
 */
internal data class IslandSource(
    val id: String,
    val kind: IslandSourceKind,
    val lifecycle: IslandLifecycle,
    val revision: Long,
    val publisherId: String,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val title: String,
    val detail: String? = null,
    val relevanceScore: Double? = null,
)

/**
 * One immutable render input. The collections are defensive, unmodifiable copies. `sourcesById`
 * contains all known non-expired source records, including terminal records with display text
 * scrubbed; `visibleIds` contains at most three eligible source IDs.
 */
internal data class IslandSnapshot(
    val version: Long,
    val policyVersion: String,
    val policyLabel: String,
    val sourcesById: Map<String, IslandSource>,
    val visibleIds: List<String>,
    val selectedId: String?,
    val selectionOrigin: IslandSelectionOrigin,
    val presentation: IslandPresentation,
    val expandedSourceId: String?,
)

/**
 * Process-local source store and approximate presentation arbiter.
 *
 * All mutations are main-thread confined because Compose observes [snapshot]. Adapter work running
 * on another thread must post its immutable event to the main thread before calling this object.
 * Source payloads are never written to disk. Unavailable and terminal records have display text
 * scrubbed immediately. A terminal ID remains tombstoned for this process lifetime so delayed
 * callbacks cannot revive it; a new activity/session must have a new stable ID. Direct synchronous
 * sources with no delayed callbacks may explicitly discard their terminal record.
 */
internal object IslandStateEngine {
    const val POLICY_VERSION: String = "android-approx-v2"
    const val POLICY_LABEL: String =
        "active tasks before direct device status, then publisher grouping and start time; user selection retained"

    private const val MAX_VISIBLE_SOURCES = 3

    private val sourceRecords = LinkedHashMap<String, IslandSource>()
    private val terminalIds = HashSet<String>()
    private var selectedSourceId: String? = null
    private var selectionOrigin = IslandSelectionOrigin.AUTOMATIC
    private var expandedSourceId: String? = null

    private val observableSnapshot = mutableStateOf(
        IslandSnapshot(
            version = 0L,
            policyVersion = POLICY_VERSION,
            policyLabel = POLICY_LABEL,
            sourcesById = immutableMap(emptyMap()),
            visibleIds = immutableList(emptyList()),
            selectedId = null,
            selectionOrigin = IslandSelectionOrigin.AUTOMATIC,
            presentation = IslandPresentation.IDLE,
            expandedSourceId = null,
        ),
    )

    /** Compose reads this property as observable state and receives one complete snapshot/frame. */
    val snapshot: IslandSnapshot
        get() = observableSnapshot.value

    /** Adds or updates one source if its per-source revision is newer. */
    fun upsert(source: IslandSource): Boolean {
        checkMainThread()
        validate(source)

        if (source.id in terminalIds) return false
        val previous = sourceRecords[source.id]
        if (previous != null) {
            if (source.revision <= previous.revision) return false
            // An ID cannot silently change owners or source classes mid-lifecycle.
            if (source.kind != previous.kind || source.publisherId != previous.publisherId) return false
        }

        if (source.lifecycle.isTerminal()) {
            return terminalize(source.id, source.revision, source.lifecycle)
        }

        sourceRecords[source.id] = source.scrubIfUnavailable()
        publish()
        return true
    }

    /**
     * Changes only lifecycle for a known source. Reconnecting an unavailable source requires a
     * fresh [upsert] snapshot so stale or permission-revoked content is never re-exposed.
     */
    fun transition(id: String, revision: Long, lifecycle: IslandLifecycle): Boolean {
        checkMainThread()
        require(id.isNotBlank()) { "Source ID must not be blank" }
        require(revision >= 0L) { "Source revision must be non-negative" }

        if (id in terminalIds) return false
        val previous = sourceRecords[id] ?: return if (lifecycle.isTerminal()) {
            terminalize(id, revision, lifecycle)
        } else {
            false
        }
        if (revision <= previous.revision) return false
        if (previous.lifecycle == IslandLifecycle.UNAVAILABLE && !lifecycle.isTerminal()) return false

        if (lifecycle.isTerminal()) return terminalize(id, revision, lifecycle)

        sourceRecords[id] = previous.copy(
            lifecycle = lifecycle,
            revision = revision,
        ).scrubIfUnavailable()
        publish()
        return true
    }

    /**
     * Terminally removes a source from eligibility and scrubs its display text. The tombstone also
     * covers an end arriving before its delayed start snapshot.
     */
    fun remove(id: String, revision: Long): Boolean = transition(id, revision, IslandLifecycle.ENDED)

    /** Discards a terminal direct source only when its owner has no delayed callbacks for this ID. */
    fun forgetTerminal(id: String): Boolean {
        checkMainThread()
        if (id !in terminalIds || sourceRecords[id]?.kind != IslandSourceKind.SYSTEM || !id.startsWith("system:")) {
            return false
        }
        terminalIds.remove(id)
        sourceRecords.remove(id)
        publish()
        return true
    }

    /** Selects a currently eligible source manually, or restores automatic selection with null. */
    fun select(id: String?): Boolean {
        checkMainThread()
        val eligibleIds = orderedEligibleSources().map { it.id }
        if (id != null && id !in eligibleIds) return false

        val newId = id ?: eligibleIds.firstOrNull()
        val newOrigin = if (id == null) IslandSelectionOrigin.AUTOMATIC else IslandSelectionOrigin.USER
        if (selectedSourceId == newId && selectionOrigin == newOrigin) return false

        selectedSourceId = newId
        selectionOrigin = newOrigin
        if (expandedSourceId != selectedSourceId) expandedSourceId = null
        publish()
        return true
    }

    /** Expands/collapses only the selected visible source, keeping expansion bound to its ID. */
    fun setExpanded(sourceId: String, expanded: Boolean): Boolean {
        checkMainThread()
        require(sourceId.isNotBlank()) { "Source ID must not be blank" }
        if (sourceId != selectedSourceId || sourceId !in visibleSourceIds()) return false

        val nextExpandedId = if (expanded) sourceId else null
        if (expandedSourceId == nextExpandedId) return false
        expandedSourceId = nextExpandedId
        publish()
        return true
    }

    private fun terminalize(id: String, revision: Long, lifecycle: IslandLifecycle): Boolean {
        val previous = sourceRecords[id]
        if (id in terminalIds) return false
        if (previous != null && revision <= previous.revision) return false

        terminalIds += id
        if (previous != null) {
            sourceRecords[id] = previous.copy(
                lifecycle = lifecycle,
                revision = revision,
                title = "",
                detail = null,
            )
        }
        publish()
        return true
    }

    private fun publish() {
        val eligible = orderedEligibleSources()
        val retainedSelection = selectedSourceId?.takeIf { id -> eligible.any { it.id == id } }
        if (selectionOrigin == IslandSelectionOrigin.AUTOMATIC || retainedSelection == null) {
            selectedSourceId = eligible.firstOrNull()?.id
            selectionOrigin = IslandSelectionOrigin.AUTOMATIC
        }

        val selectedId = selectedSourceId
        val visible = buildList {
            if (selectedId != null) add(selectedId)
            eligible.asSequence().map { it.id }.filterNot { it == selectedId }.take(MAX_VISIBLE_SOURCES - size).forEach(::add)
        }.take(MAX_VISIBLE_SOURCES)

        if (expandedSourceId != selectedId || selectedId == null || selectedId !in visible) expandedSourceId = null

        val presentation = when {
            expandedSourceId != null -> IslandPresentation.EXPANDED
            visible.isEmpty() -> IslandPresentation.IDLE
            visible.size == 1 -> IslandPresentation.COMPACT
            else -> IslandPresentation.MULTIPLE
        }

        val previousVersion = observableSnapshot.value.version
        check(previousVersion < Long.MAX_VALUE) { "Island snapshot version exhausted" }
        observableSnapshot.value = IslandSnapshot(
            version = previousVersion + 1L,
            policyVersion = POLICY_VERSION,
            policyLabel = POLICY_LABEL,
            sourcesById = immutableMap(sourceRecords),
            visibleIds = immutableList(visible),
            selectedId = selectedId,
            selectionOrigin = selectionOrigin,
            presentation = presentation,
            expandedSourceId = expandedSourceId,
        )
    }

    /**
     * Versioned approximation: direct device status sits behind actionable tasks. Within each
     * tier, stable publisher grouping and optional same-publisher relevance determine order,
     * followed by earliest start, update time and ID. This does not claim iOS priority parity.
     */
    private fun orderedEligibleSources(): List<IslandSource> = sourceRecords.values
        .asSequence()
        .filter { it.lifecycle.isEligible() }
        .sortedWith(
            compareBy<IslandSource> { if (it.kind == IslandSourceKind.SYSTEM) 1 else 0 }
                .thenBy { it.publisherId }
                .thenComparator { left, right ->
                    if (left.publisherId == right.publisherId) compareRelevance(left.relevanceScore, right.relevanceScore) else 0
                }
                .thenBy { it.startedAtMillis }
                .thenByDescending { it.updatedAtMillis }
                .thenBy { it.id },
        )
        .toList()

    private fun visibleSourceIds(): Set<String> = snapshot.visibleIds.toSet()

    private fun validate(source: IslandSource) {
        require(source.id.isNotBlank()) { "Source ID must not be blank" }
        require(source.publisherId.isNotBlank()) { "Publisher ID must not be blank" }
        require(source.revision >= 0L) { "Source revision must be non-negative" }
        require(source.relevanceScore == null || source.relevanceScore.isFinite()) {
            "Relevance score must be finite"
        }
    }

    private fun checkMainThread() {
        check(Thread.currentThread() === android.os.Looper.getMainLooper().thread) {
            "IslandStateEngine mutations must run on the main thread"
        }
    }

    private fun IslandSource.scrubIfUnavailable(): IslandSource =
        if (lifecycle == IslandLifecycle.UNAVAILABLE) copy(title = "", detail = null) else this

    private fun IslandLifecycle.isEligible(): Boolean =
        this == IslandLifecycle.ACTIVE || this == IslandLifecycle.STALE

    private fun IslandLifecycle.isTerminal(): Boolean =
        this == IslandLifecycle.ENDED || this == IslandLifecycle.DISMISSED

    private fun compareRelevance(left: Double?, right: Double?): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> right.compareTo(left)
    }

    private fun immutableMap(values: Map<String, IslandSource>): Map<String, IslandSource> =
        Collections.unmodifiableMap(LinkedHashMap(values))

    private fun immutableList(values: List<String>): List<String> =
        Collections.unmodifiableList(ArrayList(values))
}
