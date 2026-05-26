package org.lolicode.moemusic.terminal

import kotlinx.coroutines.*
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.client.*
import org.lolicode.moemusic.api.event.*
import org.lolicode.moemusic.api.model.*
import org.lolicode.moemusic.api.service.FilterVerdict
import org.lolicode.moemusic.clientcore.audio.ClientAudioPlayerRuntime
import org.lolicode.moemusic.clientcore.audio.LavaPlayerTrackLoader
import org.lolicode.moemusic.clientcore.audio.PcmRingBuffer
import org.lolicode.moemusic.clientcore.media.ClientMediaFirewall
import org.lolicode.moemusic.clientcore.playback.ClientVolumeController
import org.lolicode.moemusic.clientcore.playback.InstancePlaybackLock
import org.lolicode.moemusic.clientcore.playback.SearchSourceCatalog
import org.lolicode.moemusic.clientcore.playback.SearchSourceInfo
import org.lolicode.moemusic.clientcore.playback.ServerWelcomeRejection
import org.lolicode.moemusic.clientcore.playback.ServerWelcomeRejectionReason
import org.lolicode.moemusic.clientcore.playback.toLocalizedText
import org.lolicode.moemusic.clientcore.request.ClientRequestTransport
import org.lolicode.moemusic.clientcore.request.DirectClientRequestService
import org.lolicode.moemusic.core.config.ClientVolume
import org.lolicode.moemusic.core.config.ContentFilterClientListMode
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.event.CoreEvents
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.media.MediaUrlPolicyResult
import org.lolicode.moemusic.core.playback.TimeSyncHandler
import org.lolicode.moemusic.core.playback.parseLyrics
import org.lolicode.moemusic.core.playback.toApi
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.*
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds

class TerminalClientRuntime(
    val configDir: Path,
    private val user: TerminalUser,
    private val scope: CoroutineScope,
) : TerminalClientPacketSink {

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

    private val logger = LoggerFactory.getLogger(TerminalClientRuntime::class.java)
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
    var statusMessage: String = "Starting MoeMusic for terminal..."
        private set

    @Volatile
    var serverClockOffset: Long = 0L
        private set

    @Volatile
    var serverHandshakeReceived: Boolean = false
        private set

    @Volatile
    private var serverSessionAccepted: Boolean = false

    @Volatile
    private var lastServerWelcomeRejection: ServerWelcomeRejection? = null

    @Volatile
    private var timeSyncEstablished: Boolean = false

    @Volatile
    private var participationRequested: Boolean = false

    @Volatile
    private var playbackRegistrationActive: Boolean = false

    val requestService: IClientRequestService = DirectClientRequestService(ClientTransport())

    val playbackService: IClientPlaybackService = object : IClientPlaybackService {
        override val currentContext: TrackContext?
            get() = this@TerminalClientRuntime.currentContext

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
            this@TerminalClientRuntime.syncParticipationWithCurrentConfig()
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
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
        stopPlaybackLockRetry()
        InstancePlaybackLock.release()
        audioRuntime.stop()
        clearConnectionState(RuntimeException("Terminal runtime stopped."))
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
                PacketIds.PLAYBACK_SNAPSHOT_UPDATE -> handlePlaybackSnapshotUpdate(PlaybackSnapshotUpdate.ADAPTER.decode(payload))
                PacketIds.STATE_UPDATE -> handleStateUpdate(StateUpdate.ADAPTER.decode(payload))
                PacketIds.SYNC_RESPONSE -> handleSyncResponse(SyncResponse.ADAPTER.decode(payload))
                PacketIds.SERVER_WELCOME -> handleServerWelcome(ServerWelcome.ADAPTER.decode(payload))
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
        } else {
            sendClientStateChange(ClientStateProto.CLIENT_STATE_STANDBY)
            stopActivePlayback(fireEvent = true)
            InstancePlaybackLock.release()
            stopPlaybackLockRetry()
            syncJob?.cancel()
            syncJob = null
        }
    }

    private fun handleServerWelcome(msg: ServerWelcome) {
        if (!participationRequested) return
        msg.initial_time_sync?.let(::applyTimeSync)
        serverHandshakeReceived = true
        serverSessionAccepted = msg.accepted
        if (!msg.accepted) {
            val rejection = serverWelcomeRejection(msg)
            lastServerWelcomeRejection = rejection
            val failure = renderServerWelcomeRejection(rejection)
            setStatus(failure)
            sourceCatalog = null
            playbackRegistrationActive = false
            syncJob?.cancel()
            syncJob = null
            stopActivePlayback(fireEvent = true)
            InstancePlaybackLock.release()
            clearPendingRequests(ClientRequestException(failure))
            return
        }
        lastServerWelcomeRejection = null

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
        playbackRegistrationActive = msg.accepted_state == ClientStateProto.CLIENT_STATE_ACTIVE
        setStatus("Connected (${sourceCatalog?.sources?.size ?: 0} sources)")
        requestQueueRefresh()
        if (playbackRegistrationActive) {
            if (ModConfigManager.config.client.playbackEnabled) {
                startSyncLoop()
                val snapshot = msg.initial_playback
                if (snapshot != null) {
                    applyPlaybackSnapshot(snapshot, fromSyncState = true)
                } else {
                    InstancePlaybackLock.release()
                    stopActivePlayback(fireEvent = true)
                }
            } else {
                sendClientStateChange(ClientStateProto.CLIENT_STATE_STANDBY)
            }
        } else {
            syncJob?.cancel()
            syncJob = null
        }
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
        if (!canHandlePlaybackPackets()) return
        applyTimeSync(response)
    }

    private fun handlePlayTrack(msg: PlayTrack) {
        applyPlaybackSnapshot(msg.snapshot ?: return, fromSyncState = false)
        requestQueueRefresh()
    }

    private fun handlePlaybackSnapshotUpdate(msg: PlaybackSnapshotUpdate) {
        if (!participationRequested || !serverSessionAccepted) return
        msg.time_sync?.let(::applyTimeSync)
        if (!ModConfigManager.config.client.playbackEnabled) {
            sendClientStateChange(ClientStateProto.CLIENT_STATE_STANDBY)
            return
        }
        playbackRegistrationActive = true
        startSyncLoop()
        val snapshot = msg.snapshot
        if (snapshot == null) {
            InstancePlaybackLock.release()
            stopActivePlayback(fireEvent = true)
            return
        }
        applyPlaybackSnapshot(snapshot, fromSyncState = true)
        requestQueueRefresh()
    }

    private fun handleStateUpdate(msg: StateUpdate) {
        if (!canHandlePlaybackPackets()) return
        val ctx = currentContext ?: return
        when (msg.state) {
            PlaybackStateProto.PAUSED -> {
                val positionMs = normalizeClientPosition(msg.position_ms, ctx.track.durationMs)
                audioRuntime.pause()
                currentContext = ctx.copy(state = PlaybackState.Paused(positionMs))
                CoreEvents.bus.fire(OnClientPlaybackPaused(ctx.track, positionMs))
            }

            PlaybackStateProto.PLAYING -> {
                val playback = msg.playback?.toApi() ?: ctx.playback
                val serverNow = currentServerMonotonicNow()
                val seekMs = anchoredPlaybackPositionMs(
                    positionMs = msg.position_ms,
                    anchorServerMonotonic = msg.position_anchor_server_monotonic,
                    durationMs = ctx.track.durationMs,
                )
                if (!ensurePlaybackLock()) return
                audioRuntime.play(playback, seekMs, ::handleAudioError)
                val wasPaused = ctx.state is PlaybackState.Paused
                currentContext = ctx.copy(
                    playback = playback,
                    state = PlaybackState.Playing(seekMs),
                    serverStartMonotonic = if (msg.position_anchor_server_monotonic != 0L) {
                        msg.position_anchor_server_monotonic - msg.position_ms.coerceAtLeast(0L) * 1_000_000L
                    } else {
                        ctx.serverStartMonotonic
                    },
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

    private fun applyPlaybackSnapshot(snapshot: PlaybackSnapshot, fromSyncState: Boolean) {
        if (!canHandlePlaybackPackets()) return
        val trackProto = snapshot.track ?: return
        val playback = snapshot.playback?.toApi() ?: return
        val track = trackProto.toApi().withLyrics(snapshot.lyric_lrc, snapshot.secondary_lyric_lrc)
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
        val serverNow = currentServerMonotonicNow()
        when (snapshot.state) {
            PlaybackStateProto.PLAYING -> {
                if (!ensurePlaybackLock()) return
                val positionMs = anchoredPlaybackPositionMs(
                    positionMs = snapshot.position_ms,
                    anchorServerMonotonic = snapshot.position_anchor_server_monotonic,
                    durationMs = allowedTrack.durationMs,
                )
                audioRuntime.play(playback, positionMs, ::handleAudioError)
                currentContext = TrackContext(
                    track = allowedTrack,
                    playback = playback,
                    state = PlaybackState.Playing(positionMs),
                    serverStartMonotonic = if (snapshot.position_anchor_server_monotonic != 0L) {
                        snapshot.position_anchor_server_monotonic - snapshot.position_ms.coerceAtLeast(0L) * 1_000_000L
                    } else {
                        serverNow - positionMs * 1_000_000L
                    },
                    serverResumeMonotonic = serverNow,
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

            PlaybackStateProto.PAUSED -> {
                if (!ensurePlaybackLock()) return
                val positionMs = normalizeClientPosition(snapshot.position_ms, allowedTrack.durationMs)
                audioRuntime.play(playback, positionMs, ::handleAudioError)
                audioRuntime.pause()
                currentContext = TrackContext(
                    track = allowedTrack,
                    playback = playback,
                    state = PlaybackState.Paused(positionMs),
                    serverStartMonotonic = serverNow - positionMs * 1_000_000L,
                    serverResumeMonotonic = serverNow,
                )
                CoreEvents.bus.fire(
                    OnClientPlaybackStarted(
                        track = allowedTrack,
                        playback = playback,
                        positionMs = positionMs,
                        fromSyncState = fromSyncState,
                    ),
                )
                setStatus("Paused ${allowedTrack.title.ifBlank { allowedTrack.id }}")
            }

            PlaybackStateProto.STOPPED -> {
                InstancePlaybackLock.release()
                stopActivePlayback(fireEvent = true)
            }
        }
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
                if (!participationRequested || !serverSessionAccepted) return@launch
                if (playbackRegistrationActive) return@launch
                if (!ModConfigManager.config.client.playbackEnabled) return@launch

                val lockAvailable = !ModConfigManager.config.client.globalInstancePlaybackLock ||
                    InstancePlaybackLock.probeAvailable()
                if (lockAvailable) {
                    setStatus("Playback lock available; resuming local audio")
                    playbackLockRetryJob = null
                    sendClientStateChange(ClientStateProto.CLIENT_STATE_ACTIVE)
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
        if (!serverSessionAccepted) return
        beginCorrelatedRequest(pendingQueueResponses, PacketIds.QUEUE_REQUEST) { requestId ->
            QueueRequest(request_id = requestId).encode()
        }
    }

    private fun sendHandshake(locale: String, initialState: ClientStateProto) {
        participationRequested = true
        playbackRegistrationActive = false
        serverHandshakeReceived = false
        serverSessionAccepted = false
        lastServerWelcomeRejection = null
        sourceCatalog = null
        val now = System.nanoTime()
        channel.sendToServer(
            PacketIds.CLIENT_HANDSHAKE,
            ClientHandshake(
                locale = locale,
                mod_version = "terminal-dev",
                protocol_version = MoeMusicProtocol.VERSION,
                initial_state = initialState,
                client_send_monotonic = now,
            ).encode(),
        )
    }

    private fun sendClientStateChange(state: ClientStateProto) {
        if (!participationRequested || !serverSessionAccepted) return
        if (state == ClientStateProto.CLIENT_STATE_STANDBY) {
            playbackRegistrationActive = false
            syncJob?.cancel()
            syncJob = null
        }
        channel.sendToServer(
            PacketIds.CLIENT_STATE_CHANGE,
            ClientStateChange(
                state = state,
                client_send_monotonic = System.nanoTime(),
            ).encode(),
        )
    }

    private fun startSyncLoop() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch {
            while (isActive) {
                if (canHandlePlaybackPackets()) {
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
        clearPendingRequests(cause)
        participationRequested = false
        playbackRegistrationActive = false
        serverHandshakeReceived = false
        serverSessionAccepted = false
        lastServerWelcomeRejection = null
        timeSyncEstablished = false
        serverClockOffset = 0L
        sourceCatalog = null
    }

    private fun currentServerMonotonicNow(): Long = System.nanoTime() + serverClockOffset

    private fun applyTimeSync(response: SyncResponse) {
        serverClockOffset = timeSyncHandler.computeClientOffset(response)
        timeSyncEstablished = true
    }

    private fun serverWelcomeRejection(msg: ServerWelcome): ServerWelcomeRejection =
        ServerWelcomeRejection(
            reason = when (msg.reject_reason) {
                ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_PROTOCOL_MISMATCH ->
                    ServerWelcomeRejectionReason.PROTOCOL_MISMATCH
                ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_SERVER_ERROR ->
                    ServerWelcomeRejectionReason.SERVER_ERROR
                ServerWelcomeRejectReason.SERVER_WELCOME_REJECT_UNSPECIFIED ->
                    if (msg.server_protocol_version != 0 && msg.server_protocol_version != MoeMusicProtocol.VERSION) {
                        ServerWelcomeRejectionReason.PROTOCOL_MISMATCH
                    } else {
                        ServerWelcomeRejectionReason.UNKNOWN
                    }
            },
            clientProtocolVersion = MoeMusicProtocol.VERSION,
            serverProtocolVersion = msg.server_protocol_version,
            detail = msg.failure.ifBlank { null },
        )

    private fun renderServerWelcomeRejection(rejection: ServerWelcomeRejection): String =
        Localization.render(user.locale, rejection.toLocalizedText())

    private fun anchoredPlaybackPositionMs(
        positionMs: Long,
        anchorServerMonotonic: Long,
        durationMs: Long,
    ): Long {
        val basePositionMs = positionMs.coerceAtLeast(0L)
        if (!timeSyncEstablished || anchorServerMonotonic == 0L) {
            return normalizeClientPosition(basePositionMs, durationMs)
        }
        val elapsedMs = (currentServerMonotonicNow() - anchorServerMonotonic) / 1_000_000L
        return normalizeClientPosition(basePositionMs + elapsedMs, durationMs)
    }

    private fun normalizeClientPosition(positionMs: Long, durationMs: Long): Long {
        val nonNegative = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0L) nonNegative.coerceAtMost(durationMs) else nonNegative
    }

    private fun canHandlePlaybackPackets(): Boolean =
        serverSessionAccepted && playbackRegistrationActive && ModConfigManager.config.client.playbackEnabled

    private fun clearPendingRequests(cause: Throwable) {
        pendingSearchResponses.failAll(cause)
        pendingQueueResponses.failAll(cause)
        pendingTrackSubmitResponses.failAll(cause)
        pendingIdentifierSubmitResponses.failAll(cause)
        pendingSelectionSubmitResponses.failAll(cause)
        pendingQueueRemoveResponses.failAll(cause)
        pendingPlaybackControlResponses.failAll(cause)
        pendingContentFilterActionResponses.failAll(cause)
    }

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
        if (!participationRequested || !serverSessionAccepted) return null
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
            check(participationRequested) { "MoeMusic terminal session is not initialized." }
            check(serverHandshakeReceived) { "MoeMusic server handshake has not completed yet." }
            check(serverSessionAccepted) {
                lastServerWelcomeRejection?.let(::renderServerWelcomeRejection)
                    ?: Localization.render(user.locale, LocalizedText.key("screen.moemusic.unavailable.rejected.body"))
            }
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
