package org.lolicode.moemusic.standalone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.client.ClientAvailabilityIssue
import org.lolicode.moemusic.api.client.ClientSearchCatalog
import org.lolicode.moemusic.api.client.ClientSearchSource
import org.lolicode.moemusic.api.client.ClientVolumeOverride
import org.lolicode.moemusic.api.client.IClientPlaybackService
import org.lolicode.moemusic.api.client.IClientRequestService
import org.lolicode.moemusic.api.event.OnClientConnected
import org.lolicode.moemusic.api.event.OnClientDisconnected
import org.lolicode.moemusic.api.event.OnClientPlaybackPaused
import org.lolicode.moemusic.api.event.OnClientPlaybackResumed
import org.lolicode.moemusic.api.event.OnClientPlaybackSeeked
import org.lolicode.moemusic.api.event.OnClientPlaybackStarted
import org.lolicode.moemusic.api.event.OnClientPlaybackStopped
import org.lolicode.moemusic.api.event.UserParticipationState
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.PlaybackState
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackContext
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.directTrackId
import org.lolicode.moemusic.api.service.FilterVerdict
import org.lolicode.moemusic.clientcore.audio.ClientAudioPlayerRuntime
import org.lolicode.moemusic.clientcore.audio.LavaPlayerTrackLoader
import org.lolicode.moemusic.clientcore.audio.PcmRingBuffer
import org.lolicode.moemusic.clientcore.media.ClientMediaFirewall
import org.lolicode.moemusic.clientcore.playback.ClientVolumeController
import org.lolicode.moemusic.clientcore.playback.InstancePlaybackLock
import org.lolicode.moemusic.clientcore.playback.SearchSourceCatalog
import org.lolicode.moemusic.clientcore.playback.SearchSourceInfo
import org.lolicode.moemusic.clientcore.request.ClientRequestTransport
import org.lolicode.moemusic.clientcore.request.DirectClientRequestService
import org.lolicode.moemusic.core.config.ContentFilterClientListMode
import org.lolicode.moemusic.core.config.ClientVolume
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.media.MediaUrlPolicyResult
import org.lolicode.moemusic.core.playback.TimeSyncHandler
import org.lolicode.moemusic.core.playback.parseLyrics
import org.lolicode.moemusic.core.playback.toApi
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.ClientHandshake
import org.lolicode.moemusic.core.protocol.proto.ClientStateChange
import org.lolicode.moemusic.core.protocol.proto.ClientStateProto
import org.lolicode.moemusic.core.protocol.proto.ContentFilterActionProto
import org.lolicode.moemusic.core.protocol.proto.ContentFilterActionRequest
import org.lolicode.moemusic.core.protocol.proto.ContentFilterActionResponse
import org.lolicode.moemusic.core.protocol.proto.ContentFilterTargetProto
import org.lolicode.moemusic.core.protocol.proto.IdentifierSubmitRequest
import org.lolicode.moemusic.core.protocol.proto.IdentifierSubmitResponse
import org.lolicode.moemusic.core.protocol.proto.PlayTrack
import org.lolicode.moemusic.core.protocol.proto.PlaybackControlAction
import org.lolicode.moemusic.core.protocol.proto.PlaybackControlRequest
import org.lolicode.moemusic.core.protocol.proto.PlaybackControlResponse
import org.lolicode.moemusic.core.protocol.proto.PlaybackStateProto
import org.lolicode.moemusic.core.protocol.proto.QueueRemoveRequest
import org.lolicode.moemusic.core.protocol.proto.QueueRemoveResponse
import org.lolicode.moemusic.core.protocol.proto.QueueRequest
import org.lolicode.moemusic.core.protocol.proto.QueueResponse
import org.lolicode.moemusic.core.protocol.proto.SearchRequest
import org.lolicode.moemusic.core.protocol.proto.SearchResponse
import org.lolicode.moemusic.core.protocol.proto.SelectionSubmitRequest
import org.lolicode.moemusic.core.protocol.proto.SelectionSubmitResponse
import org.lolicode.moemusic.core.protocol.proto.ServerHandshake
import org.lolicode.moemusic.core.protocol.proto.StateUpdate
import org.lolicode.moemusic.core.protocol.proto.SyncRequest
import org.lolicode.moemusic.core.protocol.proto.SyncResponse
import org.lolicode.moemusic.core.protocol.proto.SyncState
import org.lolicode.moemusic.core.protocol.proto.TrackSubmitRequest
import org.lolicode.moemusic.core.protocol.proto.TrackSubmitResponse
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

class StandaloneClientRuntime(
    val configDir: Path,
    private val user: StandaloneUser,
    private val scope: CoroutineScope,
) : StandaloneClientPacketSink {

    private class PendingRequestRegistry<T> {
        private val pending = ConcurrentHashMap<Long, CompletableDeferred<T>>()

        fun register(requestId: Long): CompletableDeferred<T> =
            CompletableDeferred<T>().also { deferred ->
                pending[requestId] = deferred
                deferred.invokeOnCompletion {
                    pending.remove(requestId, deferred)
                }
            }

        fun complete(requestId: Long, response: T) {
            pending.remove(requestId)?.complete(response)
        }

        fun failAll(cause: Throwable) {
            val entries = pending.values.toList()
            pending.clear()
            entries.forEach { it.completeExceptionally(cause) }
        }
    }

    private val logger = LoggerFactory.getLogger(StandaloneClientRuntime::class.java)
    private val timeSyncHandler = TimeSyncHandler()
    private val requestIdCounter = AtomicLong(1L)
    private val pendingSearchResponses = PendingRequestRegistry<SearchResponse>()
    private val pendingQueueResponses = PendingRequestRegistry<QueueResponse>()
    private val pendingTrackSubmitResponses = PendingRequestRegistry<TrackSubmitResponse>()
    private val pendingIdentifierSubmitResponses = PendingRequestRegistry<IdentifierSubmitResponse>()
    private val pendingSelectionSubmitResponses = PendingRequestRegistry<SelectionSubmitResponse>()
    private val pendingQueueRemoveResponses = PendingRequestRegistry<QueueRemoveResponse>()
    private val pendingPlaybackControlResponses = PendingRequestRegistry<PlaybackControlResponse>()
    private val pendingContentFilterActionResponses = PendingRequestRegistry<ContentFilterActionResponse>()
    private var latestSearchResponseRequestId: Long = 0L

    private val ringBuffer = PcmRingBuffer()
    private val loader = LavaPlayerTrackLoader()
    private var playbackPositionReporter: (Long) -> Unit = {}
    private val output = JavaSoundAudioOutput(ringBuffer) { playbackPositionReporter(it) }
    private val audioRuntime = ClientAudioPlayerRuntime(
        ringBuffer = ringBuffer,
        loader = loader,
        output = output,
    )
    private val volumeController = ClientVolumeController { gain -> audioRuntime.setVolume(gain) }

    private lateinit var channel: InMemoryNetworkChannel
    private var syncJob: Job? = null
    private var playbackLockRetryJob: Job? = null

    @Volatile
    var currentContext: TrackContext? = null
        private set

    @Volatile
    var sourceCatalog: SearchSourceCatalog? = null
        private set

    @Volatile
    var searchResults: List<SelectionEntry> = emptyList()
        private set

    @Volatile
    private var rawSearchResults: List<SelectionEntry> = emptyList()

    @Volatile
    var searchQuery: String = ""
        private set

    @Volatile
    var searchResultSourceId: String = ""
        private set

    @Volatile
    var searchLoadedCount: Int = 0
        private set

    @Volatile
    var searchTotal: Int = -1
        private set

    @Volatile
    var searchHasMore: Boolean = false
        private set

    @Volatile
    var searchFailure: String? = null
        private set

    @Volatile
    var queueTracks: List<TrackInfo> = emptyList()
        private set

    @Volatile
    var queueFailure: String? = null
        private set

    @Volatile
    var statusMessage: String = "Starting MoeMusic standalone..."
        private set

    @Volatile
    var serverClockOffset: Long = 0L
        private set

    @Volatile
    var serverHandshakeReceived: Boolean = false
        private set

    @Volatile
    private var participationRequested: Boolean = false

    @Volatile
    private var playbackRegistrationActive: Boolean = false

    val requestService: IClientRequestService = DirectClientRequestService(ClientTransport())

    val playbackService: IClientPlaybackService = object : IClientPlaybackService {
        override val currentContext: TrackContext?
            get() = this@StandaloneClientRuntime.currentContext

        override val searchCatalog: ClientSearchCatalog?
            get() = sourceCatalog?.let { catalog ->
                ClientSearchCatalog(
                    sources = catalog.sources.map { source ->
                        ClientSearchSource(source.id, source.displayName, source.searchable)
                    },
                    defaultSourceId = catalog.defaultSourceId,
                )
            }

        override val currentParticipationState: UserParticipationState?
            get() = when {
                !participationRequested -> null
                playbackRegistrationActive -> UserParticipationState.ACTIVE
                else -> UserParticipationState.STANDBY
            }

        override val currentAvailabilityIssue: ClientAvailabilityIssue?
            get() = null

        override val configuredVolumePercent: Int
            get() = volumeController.configuredVolumePercent

        override val effectiveVolumePercent: Int
            get() = volumeController.effectiveVolumePercent

        override fun currentPositionMs(): Long? =
            currentContext?.let(::currentPositionMs)

        override fun setConfiguredVolumePercent(percent: Int) {
            val normalized = ClientVolume.normalizePercent(percent)
            volumeController.setConfiguredVolumePercent(normalized)
            ModConfigManager.updateClient { client -> client.copy(volume = normalized) }
            setStatus("Volume $normalized%")
        }

        override fun setTransientVolumeOverride(ownerId: String, override: ClientVolumeOverride) {
            volumeController.setTransientOverride(ownerId, override)
        }

        override fun clearTransientVolumeOverride(ownerId: String) {
            volumeController.clearTransientOverride(ownerId)
        }

        override fun isPlaybackEnabledForCurrentServer(): Boolean =
            ModConfigManager.config.client.playbackEnabled

        override fun setPlaybackEnabledForCurrentServer(enabled: Boolean) {
            ModConfigManager.updateClient { client -> client.copy(playbackEnabled = enabled) }
            syncParticipationWithCurrentConfig()
        }

        override fun syncParticipationWithCurrentConfig() {
            this@StandaloneClientRuntime.syncParticipationWithCurrentConfig()
        }
    }

    init {
        playbackPositionReporter = audioRuntime::reportPlaybackPosition
        reloadClientConfig()
    }

    fun attachChannel(channel: InMemoryNetworkChannel) {
        this.channel = channel
    }

    fun start() {
        CoreEvents.bus.fire(OnClientConnected)
        sendHandshake(
            locale = user.locale,
            initialState = if (ModConfigManager.config.client.playbackEnabled) {
                ClientStateProto.CLIENT_STATE_ACTIVE
            } else {
                ClientStateProto.CLIENT_STATE_STANDBY
            },
        )
        startSyncLoop()
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        stopPlaybackLockRetry()
        InstancePlaybackLock.release()
        audioRuntime.stop()
        clearConnectionState(RuntimeException("Standalone runtime stopped."))
        CoreEvents.bus.fire(OnClientDisconnected)
    }

    fun reloadClientConfig() {
        ContentFilterRuntime.applyConfig(ModConfigManager.config)
        volumeController.setConfiguredVolumePercent(ModConfigManager.config.client.volume)
    }

    fun setStatus(message: String) {
        statusMessage = message
    }

    override fun receiveFromServer(packetId: PacketId, payload: ByteArray) {
        try {
            when (packetId) {
                PacketIds.PLAY_TRACK -> handlePlayTrack(PlayTrack.ADAPTER.decode(payload))
                PacketIds.SYNC_STATE -> handleSyncState(SyncState.ADAPTER.decode(payload))
                PacketIds.STATE_UPDATE -> handleStateUpdate(StateUpdate.ADAPTER.decode(payload))
                PacketIds.SYNC_RESPONSE -> handleSyncResponse(SyncResponse.ADAPTER.decode(payload))
                PacketIds.SERVER_HANDSHAKE -> handleServerHandshake(ServerHandshake.ADAPTER.decode(payload))
                PacketIds.SEARCH_RESPONSE -> handleSearchResponse(SearchResponse.ADAPTER.decode(payload))
                PacketIds.QUEUE_RESPONSE -> handleQueueResponse(QueueResponse.ADAPTER.decode(payload))
                PacketIds.TRACK_SUBMIT_RESPONSE -> handleTrackSubmitResponse(TrackSubmitResponse.ADAPTER.decode(payload))
                PacketIds.IDENTIFIER_SUBMIT_RESPONSE -> handleIdentifierSubmitResponse(IdentifierSubmitResponse.ADAPTER.decode(payload))
                PacketIds.SELECTION_SUBMIT_RESPONSE -> handleSelectionSubmitResponse(SelectionSubmitResponse.ADAPTER.decode(payload))
                PacketIds.QUEUE_REMOVE_RESPONSE -> handleQueueRemoveResponse(QueueRemoveResponse.ADAPTER.decode(payload))
                PacketIds.PLAYBACK_CONTROL_RESPONSE -> handlePlaybackControlResponse(PlaybackControlResponse.ADAPTER.decode(payload))
                PacketIds.CONTENT_FILTER_ACTION_RESPONSE ->
                    handleContentFilterActionResponse(ContentFilterActionResponse.ADAPTER.decode(payload))

                else -> logger.debug("Ignoring unsupported S2C packet {}", packetId)
            }
        } catch (error: Exception) {
            logger.error("Failed to handle S2C packet {}: {}", packetId, error.message, error)
            setStatus("Packet error: ${error.message}")
        }
    }

    fun currentPositionMs(ctx: TrackContext): Long =
        when (val state = ctx.state) {
            is PlaybackState.Playing -> audioRuntime.currentPositionMs()
            is PlaybackState.Paused -> state.positionMs
            PlaybackState.Stopped -> 0L
        }.coerceAtMost(ctx.track.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)

    fun syncParticipationWithCurrentConfig() {
        val active = ModConfigManager.config.client.playbackEnabled
        if (active == playbackRegistrationActive) return
        if (active) {
            sendClientStateChange(ClientStateProto.CLIENT_STATE_ACTIVE)
            startSyncLoop()
        } else {
            sendClientStateChange(ClientStateProto.CLIENT_STATE_STANDBY)
            stopActivePlayback(fireEvent = true)
            InstancePlaybackLock.release()
            stopPlaybackLockRetry()
            syncJob?.cancel()
            syncJob = null
        }
    }

    private fun handleServerHandshake(msg: ServerHandshake) {
        serverHandshakeReceived = true
        sourceCatalog = SearchSourceCatalog(
            sources = msg.sources.map { source ->
                SearchSourceInfo(
                    id = source.id,
                    displayName = source.display_name.ifBlank { source.id },
                    searchable = source.searchable,
                )
            },
            defaultSourceId = msg.default_source_id,
        )
        setStatus("Connected (${sourceCatalog?.sources?.size ?: 0} sources)")
    }

    private fun handleSearchResponse(msg: SearchResponse) {
        if (msg.request_id == 0L || msg.request_id >= latestSearchResponseRequestId) {
            latestSearchResponseRequestId = msg.request_id
            val pageEntries = msg.entries.map { it.toApi() }
            val failure = msg.failure.ifEmpty { null }
            val sameSearch = searchQuery == msg.query && searchResultSourceId == msg.source_id
            rawSearchResults = when {
                msg.offset <= 0 -> pageEntries
                !sameSearch -> pageEntries
                failure != null -> rawSearchResults
                msg.offset <= rawSearchResults.size -> rawSearchResults.take(msg.offset) + pageEntries
                else -> rawSearchResults + pageEntries
            }
            searchResults = visibleSearchResults(rawSearchResults)
            searchLoadedCount = rawSearchResults.size
            searchTotal = msg.total
            searchHasMore = msg.has_more
            searchFailure = failure
            searchQuery = msg.query
            searchResultSourceId = msg.source_id
            setStatus(
                searchFailure ?: buildString {
                    append("Search loaded ")
                    append(searchLoadedCount)
                    if (searchTotal >= 0) {
                        append("/")
                        append(searchTotal)
                    }
                    append(" result(s)")
                },
            )
        }
        pendingSearchResponses.complete(msg.request_id, msg)
    }

    private fun handleQueueResponse(msg: QueueResponse) {
        pendingQueueResponses.complete(msg.request_id, msg)
        queueTracks = visibleQueueTracks(msg.tracks.map { it.toApi() })
        queueFailure = msg.failure.ifEmpty { null }
    }

    private fun handleTrackSubmitResponse(msg: TrackSubmitResponse) {
        pendingTrackSubmitResponses.complete(msg.request_id, msg)
        setStatus(msg.success.ifEmpty { msg.failure.ifEmpty { "Submit completed" } })
        requestQueueRefresh()
    }

    private fun handleIdentifierSubmitResponse(msg: IdentifierSubmitResponse) {
        pendingIdentifierSubmitResponses.complete(msg.request_id, msg)
        if (msg.choices.isNotEmpty()) {
            rawSearchResults = msg.choices.map { it.toApi() }
            searchResults = visibleSearchResults(rawSearchResults)
            searchLoadedCount = rawSearchResults.size
            searchTotal = rawSearchResults.size
            searchHasMore = false
            searchQuery = ""
            searchResultSourceId = rawSearchResults.firstOrNull()?.sourceId.orEmpty()
            searchFailure = null
            setStatus("Identifier returned ${searchResults.size} choice(s)")
        } else {
            setStatus(msg.success.ifEmpty { msg.failure.ifEmpty { "Identifier submit completed" } })
        }
        requestQueueRefresh()
    }

    private fun handleSelectionSubmitResponse(msg: SelectionSubmitResponse) {
        pendingSelectionSubmitResponses.complete(msg.request_id, msg)
        if (msg.choices.isNotEmpty()) {
            rawSearchResults = msg.choices.map { it.toApi() }
            searchResults = visibleSearchResults(rawSearchResults)
            searchLoadedCount = rawSearchResults.size
            searchTotal = rawSearchResults.size
            searchHasMore = false
            searchQuery = ""
            searchResultSourceId = rawSearchResults.firstOrNull()?.sourceId.orEmpty()
            searchFailure = null
            setStatus("Selection returned ${searchResults.size} choice(s)")
        } else {
            setStatus(msg.success.ifEmpty { msg.failure.ifEmpty { "Selection submit completed" } })
        }
        requestQueueRefresh()
    }

    private fun handleQueueRemoveResponse(msg: QueueRemoveResponse) {
        pendingQueueRemoveResponses.complete(msg.request_id, msg)
        setStatus(msg.failure.ifEmpty { "Removed queued track" })
        requestQueueRefresh()
    }

    private fun handlePlaybackControlResponse(msg: PlaybackControlResponse) {
        pendingPlaybackControlResponses.complete(msg.request_id, msg)
        setStatus(msg.success.ifEmpty { msg.failure.ifEmpty { "Playback control sent" } })
    }

    private fun handleContentFilterActionResponse(msg: ContentFilterActionResponse) {
        pendingContentFilterActionResponses.complete(msg.request_id, msg)
        refreshVisibleContentFilterState()
        setStatus(msg.success.ifEmpty { msg.failure.ifEmpty { "Content filter updated" } })
    }

    fun refreshVisibleContentFilterState() {
        searchResults = visibleSearchResults(rawSearchResults)
        queueTracks = visibleQueueTracks(queueTracks)
    }

    private fun visibleSearchResults(entries: List<SelectionEntry>): List<SelectionEntry> {
        if (!ContentFilterRuntime.clientFilterEnabled()) return entries
        if (ContentFilterRuntime.searchListMode() != ContentFilterClientListMode.HIDE) return entries
        return entries.filter { ContentFilterRuntime.selectionFilterVerdict(it) is FilterVerdict.Allow }
    }

    private fun visibleQueueTracks(tracks: List<TrackInfo>): List<TrackInfo> {
        if (!ContentFilterRuntime.clientFilterEnabled()) return tracks
        if (ContentFilterRuntime.queueListMode() != ContentFilterClientListMode.HIDE) return tracks
        return tracks.filter { ContentFilterRuntime.trackFilterVerdict(it) is FilterVerdict.Allow }
    }

    private fun handleSyncResponse(response: SyncResponse) {
        serverClockOffset = timeSyncHandler.computeClientOffset(response)
    }

    private fun handlePlayTrack(msg: PlayTrack) {
        val trackProto = msg.track ?: return
        val playback = msg.playback?.toApi() ?: return
        val track = trackProto.toApi().withLyrics(msg.lyric_lrc, msg.secondary_lyric_lrc)
        startPlayback(track, playback, msg.server_start_monotonic, fromSyncState = false, pausedPositionMs = null)
        requestQueueRefresh()
    }

    private fun handleSyncState(msg: SyncState) {
        val trackProto = msg.track ?: return
        val playback = msg.playback?.toApi() ?: return
        val track = trackProto.toApi().withLyrics(msg.lyric_lrc, msg.secondary_lyric_lrc)
        when (msg.state) {
            PlaybackStateProto.PLAYING ->
                startPlayback(track, playback, msg.server_start_monotonic, fromSyncState = true, pausedPositionMs = null)

            PlaybackStateProto.PAUSED ->
                startPlayback(track, playback, msg.server_start_monotonic, fromSyncState = true, pausedPositionMs = msg.pause_position_ms)

            PlaybackStateProto.STOPPED -> {
                InstancePlaybackLock.release()
                stopActivePlayback(fireEvent = true)
            }
        }
    }

    private fun handleStateUpdate(msg: StateUpdate) {
        val ctx = currentContext ?: return
        when (msg.state) {
            PlaybackStateProto.PAUSED -> {
                audioRuntime.pause()
                currentContext = ctx.copy(state = PlaybackState.Paused(msg.position_ms))
                CoreEvents.bus.fire(OnClientPlaybackPaused(ctx.track, msg.position_ms))
            }

            PlaybackStateProto.PLAYING -> {
                val playback = msg.playback?.toApi() ?: ctx.playback
                val serverNow = currentServerMonotonicNow()
                val seekMs = if (msg.server_start_monotonic != 0L) {
                    computeSeekMs(msg.server_start_monotonic, serverNow)
                } else {
                    msg.position_ms
                }
                if (!ensurePlaybackLock()) return
                audioRuntime.play(playback, seekMs, ::handleAudioError)
                val wasPaused = ctx.state is PlaybackState.Paused
                currentContext = ctx.copy(
                    playback = playback,
                    state = PlaybackState.Playing(seekMs),
                    serverStartMonotonic = msg.server_start_monotonic.takeIf { it != 0L } ?: ctx.serverStartMonotonic,
                    serverResumeMonotonic = serverNow,
                )
                if (wasPaused) {
                    CoreEvents.bus.fire(OnClientPlaybackResumed(ctx.track, seekMs))
                } else {
                    CoreEvents.bus.fire(OnClientPlaybackSeeked(ctx.track, seekMs))
                }
            }

            PlaybackStateProto.STOPPED -> {
                InstancePlaybackLock.release()
                stopActivePlayback(fireEvent = true)
            }
        }
    }

    private fun startPlayback(
        track: TrackInfo,
        playback: PlaybackResource,
        serverStartMonotonic: Long,
        fromSyncState: Boolean,
        pausedPositionMs: Long?,
    ) {
        if (!playbackRegistrationActive || !ModConfigManager.config.client.playbackEnabled) return
        if (playback.url.isBlank()) {
            stopActivePlayback(fireEvent = true)
            setStatus("Playback URL is blank")
            return
        }
        if (!applyClientMediaPolicy(track, playback.url)) {
            stopActivePlayback(fireEvent = true)
            return
        }
        val allowedTrack = applyLocalContentFilter(track) ?: run {
            stopActivePlayback(fireEvent = true)
            return
        }
        if (!ensurePlaybackLock()) return

        val serverNow = currentServerMonotonicNow()
        val positionMs = pausedPositionMs ?: computeSeekMs(serverStartMonotonic, serverNow)
        audioRuntime.play(playback, positionMs, ::handleAudioError)
        if (pausedPositionMs != null) {
            audioRuntime.pause()
        }
        currentContext = TrackContext(
            track = allowedTrack,
            playback = playback,
            state = if (pausedPositionMs == null) PlaybackState.Playing(positionMs) else PlaybackState.Paused(positionMs),
            serverStartMonotonic = serverStartMonotonic,
            serverResumeMonotonic = if (pausedPositionMs == null) serverNow else serverStartMonotonic,
        )
        CoreEvents.bus.fire(
            OnClientPlaybackStarted(
                track = allowedTrack,
                playback = playback,
                positionMs = positionMs,
                fromSyncState = fromSyncState,
            ),
        )
        setStatus("Playing ${allowedTrack.title.ifBlank { allowedTrack.id }}")
    }

    private fun applyClientMediaPolicy(track: TrackInfo, url: String): Boolean =
        when (val verdict = ClientMediaFirewall.evaluate(url)) {
            MediaUrlPolicyResult.Allow -> true
            is MediaUrlPolicyResult.Reject -> {
                setStatus(
                    Localization.render(
                        user.locale,
                        LocalizedText.key(
                            "screen.moemusic.playback.local_media_blocked",
                            track.title.ifBlank { track.id },
                            verdict.reason,
                        ),
                    ),
                )
                false
            }
        }

    private fun applyLocalContentFilter(track: TrackInfo): TrackInfo? {
        if (!ContentFilterRuntime.clientFilterEnabled()) return track
        val reason = ContentFilterRuntime.trackBlockReason(track) ?: return track
        setStatus(
            Localization.render(
                user.locale,
                LocalizedText.key(
                    "screen.moemusic.playback.local_filter_blocked",
                    track.title.ifBlank { track.id },
                    reason,
                ),
            ),
        )
        return null
    }

    private fun ensurePlaybackLock(): Boolean {
        if (!ModConfigManager.config.client.globalInstancePlaybackLock) {
            stopPlaybackLockRetry()
            return true
        }
        if (InstancePlaybackLock.tryAcquire()) {
            stopPlaybackLockRetry()
            return true
        }
        enterPlaybackLockStandby()
        return false
    }

    private fun enterPlaybackLockStandby() {
        setStatus("Playback standby: another MoeMusic instance owns the local audio lock")
        sendClientStateChange(ClientStateProto.CLIENT_STATE_STANDBY)
        stopActivePlayback(fireEvent = true)
        startPlaybackLockRetry()
    }

    private fun startPlaybackLockRetry() {
        if (playbackLockRetryJob?.isActive == true) return
        playbackLockRetryJob = scope.launch {
            while (isActive) {
                delay(LOCK_RETRY_INTERVAL_MS.milliseconds)
                if (!participationRequested || !serverHandshakeReceived) return@launch
                if (playbackRegistrationActive) return@launch
                if (!ModConfigManager.config.client.playbackEnabled) return@launch

                val lockAvailable = !ModConfigManager.config.client.globalInstancePlaybackLock ||
                    InstancePlaybackLock.probeAvailable()
                if (lockAvailable) {
                    setStatus("Playback lock available; resuming local audio")
                    playbackLockRetryJob = null
                    sendClientStateChange(ClientStateProto.CLIENT_STATE_ACTIVE)
                    startSyncLoop()
                    return@launch
                }
            }
        }
    }

    private fun stopPlaybackLockRetry() {
        playbackLockRetryJob?.cancel()
        playbackLockRetryJob = null
    }

    private fun stopActivePlayback(fireEvent: Boolean) {
        val stoppedTrack = currentContext?.track
        audioRuntime.stop()
        currentContext = null
        if (fireEvent && stoppedTrack != null) {
            CoreEvents.bus.fire(OnClientPlaybackStopped(stoppedTrack))
        }
    }

    private fun handleAudioError(message: String) {
        setStatus("Audio error: $message")
    }

    private fun requestQueueRefresh() {
        if (!serverHandshakeReceived) return
        beginCorrelatedRequest(pendingQueueResponses, PacketIds.QUEUE_REQUEST) { requestId ->
            QueueRequest(request_id = requestId).encode()
        }
    }

    private fun sendHandshake(locale: String, initialState: ClientStateProto) {
        participationRequested = true
        playbackRegistrationActive = initialState == ClientStateProto.CLIENT_STATE_ACTIVE
        channel.sendToServer(
            PacketIds.CLIENT_HANDSHAKE,
            ClientHandshake(
                locale = locale,
                mod_version = "standalone-dev",
                protocol_version = org.lolicode.moemusic.core.protocol.MoeMusicProtocol.VERSION,
                initial_state = initialState,
            ).encode(),
        )
    }

    private fun sendClientStateChange(state: ClientStateProto) {
        if (!participationRequested) return
        playbackRegistrationActive = state == ClientStateProto.CLIENT_STATE_ACTIVE
        channel.sendToServer(PacketIds.CLIENT_STATE_CHANGE, ClientStateChange(state = state).encode())
    }

    private fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            delay(500.milliseconds)
            while (isActive) {
                if (playbackRegistrationActive) {
                    channel.sendToServer(
                        PacketIds.SYNC_REQUEST,
                        SyncRequest(client_send_monotonic = System.nanoTime()).encode(),
                    )
                }
                delay(SYNC_INTERVAL_MS.milliseconds)
            }
        }
    }

    private fun clearConnectionState(cause: Throwable) {
        pendingSearchResponses.failAll(cause)
        pendingQueueResponses.failAll(cause)
        pendingTrackSubmitResponses.failAll(cause)
        pendingIdentifierSubmitResponses.failAll(cause)
        pendingSelectionSubmitResponses.failAll(cause)
        pendingQueueRemoveResponses.failAll(cause)
        pendingPlaybackControlResponses.failAll(cause)
        pendingContentFilterActionResponses.failAll(cause)
        participationRequested = false
        playbackRegistrationActive = false
        serverHandshakeReceived = false
        sourceCatalog = null
    }

    private fun currentServerMonotonicNow(): Long = System.nanoTime() + serverClockOffset

    private fun computeSeekMs(serverStartMonotonic: Long, serverNow: Long = currentServerMonotonicNow()): Long =
        ((serverNow - serverStartMonotonic) / 1_000_000L).coerceAtLeast(0L)

    private fun TrackInfo.withLyrics(primary: String, secondary: String): TrackInfo = copy(
        lyricLrc = primary.ifEmpty { null },
        secondaryLyricLrc = secondary.ifEmpty { null },
        lyricsFetched = primary.isNotEmpty() || secondary.isNotEmpty(),
    )

    fun currentLyricLine(): String? {
        val ctx = currentContext ?: return null
        val position = currentPositionMs(ctx)
        return parseLyrics(ctx.track.lyricLrc)?.lineAt(position)?.text
    }

    private fun nextRequestId(): Long =
        requestIdCounter.getAndIncrement().coerceAtLeast(1L)

    private fun <T> beginCorrelatedRequest(
        registry: PendingRequestRegistry<T>,
        packetId: PacketId,
        payloadFactory: (Long) -> ByteArray,
    ): Deferred<T>? {
        if (!participationRequested) return null
        val requestId = nextRequestId()
        val deferred = registry.register(requestId)
        channel.sendToServer(packetId, payloadFactory(requestId))
        return deferred
    }

    private fun TrackAddMode.toProto(): org.lolicode.moemusic.core.protocol.proto.TrackAddModeProto =
        when (this) {
            TrackAddMode.NORMAL -> org.lolicode.moemusic.core.protocol.proto.TrackAddModeProto.TRACK_ADD_MODE_NORMAL
            TrackAddMode.SKIP_AUTOPLAY -> org.lolicode.moemusic.core.protocol.proto.TrackAddModeProto.TRACK_ADD_MODE_SKIP_AUTOPLAY
            TrackAddMode.PLAY_NOW -> org.lolicode.moemusic.core.protocol.proto.TrackAddModeProto.TRACK_ADD_MODE_PLAY_NOW
        }

    private fun SelectionEntry.toDirectTrackSubmitTrack(): TrackInfo? {
        val trackId = directTrackId?.takeIf(String::isNotBlank) ?: return null
        return TrackInfo(
            id = trackId,
            title = title,
            artists = artists,
            durationMs = durationMs,
            sourceId = sourceId,
            album = album,
            unavailableReason = unavailableReason,
        )
    }

    private inner class ClientTransport : ClientRequestTransport {
        override fun ensureDirectRequestSessionReady() {
            check(participationRequested) { "MoeMusic standalone session is not initialized." }
            check(serverHandshakeReceived) { "MoeMusic server handshake has not completed yet." }
        }

        override fun beginSearchRequest(query: String, sourceId: String, limit: Int, offset: Int): Deferred<SearchResponse>? =
            beginCorrelatedRequest(pendingSearchResponses, PacketIds.SEARCH_REQUEST) { requestId ->
                SearchRequest(
                    query = query,
                    source_id = sourceId,
                    limit = limit,
                    offset = offset,
                    request_id = requestId,
                ).encode()
            }

        override fun beginQueueRequest(): Deferred<QueueResponse>? =
            beginCorrelatedRequest(pendingQueueResponses, PacketIds.QUEUE_REQUEST) { requestId ->
                QueueRequest(request_id = requestId).encode()
            }

        override fun beginQueueRemoveRequest(sourceId: String, trackId: String): Deferred<QueueRemoveResponse>? =
            beginCorrelatedRequest(pendingQueueRemoveResponses, PacketIds.QUEUE_REMOVE_REQUEST) { requestId ->
                QueueRemoveRequest(source_id = sourceId, track_id = trackId, request_id = requestId).encode()
            }

        override fun beginTrackSubmitRequest(track: TrackInfo, mode: TrackAddMode): Deferred<TrackSubmitResponse>? =
            beginCorrelatedRequest(pendingTrackSubmitResponses, PacketIds.TRACK_SUBMIT) { requestId ->
                TrackSubmitRequest(
                    source_id = track.sourceId.orEmpty(),
                    track_id = track.id,
                    mode = mode.toProto(),
                    request_id = requestId,
                ).encode()
            }

        override fun beginTrackSubmitRequest(entry: SelectionEntry, mode: TrackAddMode): Deferred<TrackSubmitResponse>? =
            entry.toDirectTrackSubmitTrack()?.let { track -> beginTrackSubmitRequest(track, mode) }

        override fun beginIdentifierSubmitRequest(identifier: String, mode: TrackAddMode): Deferred<IdentifierSubmitResponse>? =
            beginCorrelatedRequest(pendingIdentifierSubmitResponses, PacketIds.IDENTIFIER_SUBMIT) { requestId ->
                IdentifierSubmitRequest(
                    identifier = identifier,
                    mode = mode.toProto(),
                    request_id = requestId,
                ).encode()
            }

        override fun beginSelectionSubmitRequest(entry: SelectionEntry, mode: TrackAddMode): Deferred<SelectionSubmitResponse>? =
            beginCorrelatedRequest(pendingSelectionSubmitResponses, PacketIds.SELECTION_SUBMIT) { requestId ->
                SelectionSubmitRequest(
                    source_id = entry.sourceId.orEmpty(),
                    selection_id = entry.selectionId,
                    mode = mode.toProto(),
                    request_id = requestId,
                ).encode()
            }

        override fun beginPlaybackControlRequest(
            action: PlaybackControlAction,
            positionMs: Long,
        ): Deferred<PlaybackControlResponse>? =
            beginCorrelatedRequest(pendingPlaybackControlResponses, PacketIds.PLAYBACK_CONTROL_REQUEST) { requestId ->
                PlaybackControlRequest(action = action, position_ms = positionMs, request_id = requestId).encode()
            }

        override fun beginContentFilterTrackActionRequest(
            sourceId: String,
            trackId: String,
            note: String?,
            ban: Boolean,
        ): Deferred<ContentFilterActionResponse>? =
            beginCorrelatedRequest(pendingContentFilterActionResponses, PacketIds.CONTENT_FILTER_ACTION_REQUEST) { requestId ->
                ContentFilterActionRequest(
                    action = if (ban) {
                        ContentFilterActionProto.CONTENT_FILTER_ACTION_BAN
                    } else {
                        ContentFilterActionProto.CONTENT_FILTER_ACTION_UNBAN
                    },
                    target = ContentFilterTargetProto.CONTENT_FILTER_TARGET_TRACK,
                    source_id = sourceId,
                    value_id = trackId,
                    note = note.orEmpty(),
                    request_id = requestId,
                ).encode()
            }

        override fun beginContentFilterArtistActionRequest(
            sourceId: String,
            artistId: String,
            note: String?,
            ban: Boolean,
        ): Deferred<ContentFilterActionResponse>? =
            beginCorrelatedRequest(pendingContentFilterActionResponses, PacketIds.CONTENT_FILTER_ACTION_REQUEST) { requestId ->
                ContentFilterActionRequest(
                    action = if (ban) {
                        ContentFilterActionProto.CONTENT_FILTER_ACTION_BAN
                    } else {
                        ContentFilterActionProto.CONTENT_FILTER_ACTION_UNBAN
                    },
                    target = ContentFilterTargetProto.CONTENT_FILTER_TARGET_ARTIST,
                    source_id = sourceId,
                    value_id = artistId,
                    note = note.orEmpty(),
                    request_id = requestId,
                ).encode()
            }
    }

    private companion object {
        private const val SYNC_INTERVAL_MS = 30_000L
        private const val LOCK_RETRY_INTERVAL_MS = 1_000L
    }
}
