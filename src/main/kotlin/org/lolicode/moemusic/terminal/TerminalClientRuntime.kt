package org.lolicode.moemusic.terminal

import kotlinx.coroutines.CoroutineScope
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.client.*
import org.lolicode.moemusic.api.event.UserParticipationState
import org.lolicode.moemusic.api.model.PlaybackResource
import org.lolicode.moemusic.api.model.PlaybackState
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackContext
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.service.FilterVerdict
import org.lolicode.moemusic.clientcore.audio.ClientAudioFailure
import org.lolicode.moemusic.clientcore.audio.ClientAudioPlayerRuntime
import org.lolicode.moemusic.clientcore.audio.LavaPlayerTrackLoader
import org.lolicode.moemusic.clientcore.audio.PcmRingBuffer
import org.lolicode.moemusic.clientcore.playback.*
import org.lolicode.moemusic.clientcore.request.DirectClientRequestService
import org.lolicode.moemusic.core.config.ClientConfig
import org.lolicode.moemusic.core.config.ClientVolume
import org.lolicode.moemusic.core.config.ContentFilterClientListMode
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.i18n.Localization
import org.lolicode.moemusic.core.playback.parseLyrics
import org.lolicode.moemusic.core.playback.toApi
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.proto.*
import org.slf4j.LoggerFactory
import java.nio.file.Path

class TerminalClientRuntime(
    val configDir: Path,
    private val user: TerminalUser,
    private val scope: CoroutineScope,
) : TerminalClientPacketSink {

    private val logger = LoggerFactory.getLogger(TerminalClientRuntime::class.java)

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
    private var latestSearchResponseRequestId: Long = 0L

    private val runtime = ClientPlaybackRuntime(
        platform = TerminalPlaybackPlatform(),
        listener = TerminalPlaybackListener(),
        scope = scope,
        standbyLockPollIntervalMs = LOCK_RETRY_INTERVAL_MS,
    )

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
    private var rawQueueTracks: List<TrackInfo> = emptyList()

    @Volatile
    var queueFailure: String? = null
        private set

    @Volatile
    var statusMessage: String = "Starting MoeMusic for terminal..."
        private set

    val currentContext: TrackContext?
        get() = runtime.currentContext

    val sourceCatalog: SearchSourceCatalog?
        get() = runtime.sourceCatalog

    val serverHandshakeReceived: Boolean
        get() = runtime.serverHandshakeReceived

    val serverClockOffset: Long
        get() = runtime.serverClockOffset

    val requestService: IClientRequestService = DirectClientRequestService(runtime)

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
            get() = runtime.currentParticipationState()

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
        logger.info("Starting MoeMusic terminal client for user={} locale={}", user.displayName, user.locale)
        runtime.onConnectionJoined()
    }

    fun stop() {
        logger.info("Stopping MoeMusic terminal client for user={}", user.displayName)
        runtime.onConnectionDisconnected()
    }

    fun reloadClientConfig() {
        ContentFilterRuntime.applyConfig(ModConfigManager.config)
        volumeController.setConfiguredVolumePercent(ModConfigManager.config.client.volume)
        runtime.refreshTrackNormalization()
    }

    fun setStatus(message: String) {
        statusMessage = message
    }

    override fun receiveFromServer(packetId: PacketId, payload: ByteArray) {
        try {
            runtime.receiveFromServer(packetId, payload)
        } catch (error: Exception) {
            logger.error("Failed to handle S2C packet {}: {}", packetId, error.message, error)
            setStatus("Packet error: ${error.message}")
        }
    }

    fun currentPositionMs(ctx: TrackContext): Long =
        runtime.currentPositionMs(ctx)

    fun syncParticipationWithCurrentConfig() {
        runtime.syncParticipationWithCurrentConfig()
        if (!ModConfigManager.config.client.playbackEnabled) {
            queueTracks = emptyList()
            rawQueueTracks = emptyList()
        }
    }

    fun refreshVisibleContentFilterState() {
        searchResults = visibleSearchResults(rawSearchResults)
        queueTracks = visibleQueueTracks(rawQueueTracks)
    }

    fun currentLyricLine(): String? {
        val ctx = currentContext ?: return null
        val position = currentPositionMs(ctx)
        return parseLyrics(ctx.track.lyricLrc)?.lineAt(position)?.text
    }

    private fun requestQueueRefresh() {
        runtime.sendQueueRequest()
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

    private inner class TerminalPlaybackPlatform : ClientPlaybackPlatform {
        override val name: String = "Terminal"
        override val clientModVersion: String = "terminal-dev"
        override val clientProtocolVersion: Int = MoeMusicProtocol.VERSION
        override val audio: ClientPlaybackAudioAdapter = TerminalAudioAdapter()

        override fun hasConnection(): Boolean =
            ::channel.isInitialized

        override fun currentServerScope(): ClientServerScope? = null

        override fun currentLocale(): String = user.locale

        override fun clientConfig(): ClientConfig = ModConfigManager.config.client

        override fun sendToServer(packetId: PacketId, payload: ByteArray) {
            channel.sendToServer(packetId, payload)
        }

        override fun executeOnClientThread(block: () -> Unit) {
            block()
        }

        override fun render(text: LocalizedText): String =
            Localization.render(user.locale, text)

        override fun showPersistentWarning(title: LocalizedText, message: String) {
            setStatus(message)
        }

        override fun showLocalPlaybackBlocked(title: LocalizedText, message: String) {
            setStatus(message)
        }

        override fun showLocalPlaybackFailed(title: LocalizedText, message: String) {
            setStatus(message)
        }

        override fun onLocalPlaybackFailureFinal(track: TrackInfo, message: String) {
            setStatus("$message Skipping...")
            runtime.sendPlaybackControl(PlaybackControlAction.SKIP)
        }

        override fun showInstanceLockStandby(message: String) {
            setStatus("Playback standby: another MoeMusic instance owns the local audio lock")
        }
    }

    private inner class TerminalAudioAdapter : ClientPlaybackAudioAdapter {
        override fun play(playback: PlaybackResource, seekMs: Long, onError: (ClientAudioFailure) -> Unit) {
            audioRuntime.play(playback, seekMs, onError)
        }

        override fun pause() {
            audioRuntime.pause()
        }

        override fun stop() {
            audioRuntime.stop()
        }

        override fun setNormalizationGain(gain: Float) {
            audioRuntime.setNormalizationGain(gain)
        }

        override fun currentPositionMs(): Long =
            audioRuntime.currentPositionMs()

        override fun clearSavedState() {
            audioRuntime.clearSavedState()
        }
    }

    private inner class TerminalPlaybackListener : ClientPlaybackRuntimeListener {
        private var suppressNextStoppedStatus = false
        private var hadPlaybackStatus = false

        override fun onServerWelcomeAccepted(catalog: SearchSourceCatalog) {
            setStatus("Connected (${catalog.sources.size} sources)")
            requestQueueRefresh()
        }

        override fun onSearchResponse(response: SearchResponse) {
            if (response.request_id == 0L || response.request_id >= latestSearchResponseRequestId) {
                latestSearchResponseRequestId = response.request_id
                val pageEntries = response.entries.map { it.toApi() }
                val failure = response.failure.ifEmpty { null }
                val sameSearch = searchQuery == response.query && searchResultSourceId == response.source_id
                rawSearchResults = when {
                    response.offset <= 0 -> pageEntries
                    !sameSearch -> pageEntries
                    failure != null -> rawSearchResults
                    response.offset <= rawSearchResults.size -> rawSearchResults.take(response.offset) + pageEntries
                    else -> rawSearchResults + pageEntries
                }
                searchResults = visibleSearchResults(rawSearchResults)
                searchLoadedCount = rawSearchResults.size
                searchTotal = response.total
                searchHasMore = response.has_more
                searchFailure = failure
                searchQuery = response.query
                searchResultSourceId = response.source_id
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
        }

        override fun onQueueResponse(response: QueueResponse) {
            rawQueueTracks = response.tracks.map { it.toApi() }
            queueTracks = visibleQueueTracks(rawQueueTracks)
            queueFailure = response.failure.ifEmpty { null }
        }

        override fun onTrackSubmitResponse(response: TrackSubmitResponse) {
            setStatus(response.success.ifEmpty { response.failure.ifEmpty { "Submit completed" } })
            requestQueueRefresh()
        }

        override fun onIdentifierSubmitResponse(response: IdentifierSubmitResponse) {
            if (response.choices.isNotEmpty()) {
                rawSearchResults = response.choices.map { it.toApi() }
                searchResults = visibleSearchResults(rawSearchResults)
                searchLoadedCount = rawSearchResults.size
                searchTotal = rawSearchResults.size
                searchHasMore = false
                searchQuery = ""
                searchResultSourceId = rawSearchResults.firstOrNull()?.sourceId.orEmpty()
                searchFailure = null
                setStatus("Identifier returned ${searchResults.size} choice(s)")
            } else {
                setStatus(response.success.ifEmpty { response.failure.ifEmpty { "Identifier submit completed" } })
            }
            requestQueueRefresh()
        }

        override fun onSelectionSubmitResponse(response: SelectionSubmitResponse) {
            if (response.choices.isNotEmpty()) {
                rawSearchResults = response.choices.map { it.toApi() }
                searchResults = visibleSearchResults(rawSearchResults)
                searchLoadedCount = rawSearchResults.size
                searchTotal = rawSearchResults.size
                searchHasMore = false
                searchQuery = ""
                searchResultSourceId = rawSearchResults.firstOrNull()?.sourceId.orEmpty()
                searchFailure = null
                setStatus("Selection returned ${searchResults.size} choice(s)")
            } else {
                setStatus(response.success.ifEmpty { response.failure.ifEmpty { "Selection submit completed" } })
            }
            requestQueueRefresh()
        }

        override fun onQueueRemoveResponse(response: QueueRemoveResponse) {
            setStatus(response.failure.ifEmpty { "Removed queued track" })
            requestQueueRefresh()
        }

        override fun onPlaybackControlResponse(response: PlaybackControlResponse) {
            setStatus(response.success.ifEmpty { response.failure.ifEmpty { "Playback control sent" } })
        }

        override fun onContentFilterActionResponse(response: ContentFilterActionResponse) {
            refreshVisibleContentFilterState()
            setStatus(response.success.ifEmpty { response.failure.ifEmpty { "Content filter updated" } })
        }

        override fun onLocalPlaybackBlocked(message: String) {
            suppressNextStoppedStatus = true
            setStatus(message)
        }

        override fun onLocalPlaybackRetrying(message: String) {
            setStatus(message)
        }

        override fun onLocalPlaybackRecovered(track: TrackInfo) {
            setStatus("Playing: ${track.statusTitle()}")
        }

        override fun onLocalPlaybackFailed(message: String) {
            suppressNextStoppedStatus = true
            setStatus(message)
        }

        override fun onInstancePlaybackStandby(message: String?) {
            if (message != null) {
                suppressNextStoppedStatus = true
                setStatus("Playback standby: another MoeMusic instance owns the local audio lock")
            }
        }

        override fun onPlaybackStateChanged() {
            val ctx = currentContext
            when (ctx?.state) {
                is PlaybackState.Playing -> {
                    suppressNextStoppedStatus = false
                    hadPlaybackStatus = true
                    setStatus("Playing: ${ctx.track.statusTitle()}")
                }

                is PlaybackState.Paused -> {
                    suppressNextStoppedStatus = false
                    hadPlaybackStatus = true
                    setStatus("Paused: ${ctx.track.statusTitle()}")
                }

                PlaybackState.Stopped, null -> {
                    if (suppressNextStoppedStatus) {
                        suppressNextStoppedStatus = false
                        hadPlaybackStatus = false
                        return
                    }
                    if (hadPlaybackStatus) {
                        setStatus("Playback stopped")
                    }
                    hadPlaybackStatus = false
                }
            }
        }

        override fun onPlaybackSnapshotApplied() {
            requestQueueRefresh()
        }
    }

    private fun TrackInfo.statusTitle(): String =
        title.ifBlank { id }

    private companion object {
        private const val LOCK_RETRY_INTERVAL_MS = 1_000L
    }
}
