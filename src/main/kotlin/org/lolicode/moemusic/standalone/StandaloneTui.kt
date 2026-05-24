package org.lolicode.moemusic.standalone

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.TextCharacter
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.input.MouseAction
import com.googlecode.lanterna.input.MouseActionType
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.MouseCaptureMode
import com.googlecode.lanterna.terminal.Terminal
import kotlinx.coroutines.launch
import org.lolicode.moemusic.api.debugString
import org.lolicode.moemusic.api.model.PlaybackState
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.artistDisplay
import org.lolicode.moemusic.api.service.PlaybackAction
import org.lolicode.moemusic.core.config.ClientVolume
import org.lolicode.moemusic.core.config.ModConfigManager
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class StandaloneTui(
    private val app: StandaloneApplication,
    private val terminal: Terminal,
    private val options: Options = Options(),
) {

    data class Options(
        val mouseMode: MouseMode = MouseMode.AUTO,
        val coverMode: CoverMode = CoverMode.AUTO,
    )

    private enum class Tab(
        val number: Char,
        val label: String,
    ) {
        NOW_PLAYING('1', "Now Playing"),
        SEARCH('2', "Search"),
        QUEUE('3', "Queue"),
    }

    private enum class PromptMode(
        val label: String,
    ) {
        SEARCH("Search"),
        SUBMIT("Submit URL or identifier"),
    }

    private enum class DragTarget {
        SEEK,
        VOLUME,
    }

    private data class Rect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    ) {
        val right: Int get() = x + width - 1
        val bottom: Int get() = y + height - 1

        fun contains(px: Int, py: Int): Boolean =
            width > 0 && height > 0 && px in x..right && py in y..bottom

        fun inset(amount: Int): Rect =
            Rect(x + amount, y + amount, (width - amount * 2).coerceAtLeast(0), (height - amount * 2).coerceAtLeast(0))
    }

    private data class HitRegion(
        val rect: Rect,
        val onClick: (Int, Int) -> Unit,
    )

    private val running = AtomicBoolean(true)
    private val coverRenderer = StandaloneCoverArtRenderer(app.scope)
    private var currentTab = Tab.NOW_PLAYING
    private var selectedSearchIndex = 0
    private var selectedQueueIndex = 0
    private var selectedSearchSourceId: String? = null
    private var promptMode: PromptMode? = null
    private val promptBuffer = StringBuilder()
    private var needsClear = true
    private var forceCompleteRefresh = true
    private var hitRegions: List<HitRegion> = emptyList()
    private var nextHitRegions = mutableListOf<HitRegion>()
    private var activeDrag: DragTarget? = null
    private var activeDragRect: Rect? = null
    private var seekPreviewProgress: Float? = null
    private var lastSearchListRect: Rect? = null
    private var lastQueueListRect: Rect? = null

    fun run() {
        val screen = TerminalScreen(terminal)
        screen.startScreen()
        screen.cursorPosition = null
        screen.clear()
        requestQueue()

        try {
            while (running.get()) {
                if (screen.doResizeIfNecessary() != null) {
                    needsClear = true
                    forceCompleteRefresh = true
                }
                var key = screen.pollInput()
                while (key != null) {
                    handleInput(key)
                    key = screen.pollInput()
                }
                render(screen)
                screen.refresh(if (forceCompleteRefresh) Screen.RefreshType.COMPLETE else Screen.RefreshType.DELTA)
                forceCompleteRefresh = false
                Thread.sleep(250)
            }
        } finally {
            screen.stopScreen()
        }
    }

    private fun handleInput(key: KeyStroke) {
        if (key is MouseAction) {
            handleMouse(key)
            return
        }

        val mode = promptMode
        if (mode != null) {
            handlePromptKey(mode, key)
            return
        }

        when (key.keyType) {
            KeyType.Escape -> running.set(false)
            KeyType.Tab, KeyType.ArrowRight -> selectRelativeTab(1)
            KeyType.ReverseTab, KeyType.ArrowLeft -> selectRelativeTab(-1)
            KeyType.ArrowUp -> moveSelection(-1)
            KeyType.ArrowDown -> moveSelection(1)
            KeyType.PageUp -> moveSelection(-5)
            KeyType.PageDown -> moveSelection(5)
            KeyType.Enter -> activateSelection()
            KeyType.Character -> handleCommand(key.character)
            else -> Unit
        }
    }

    private fun handlePromptKey(mode: PromptMode, key: KeyStroke) {
        when (key.keyType) {
            KeyType.Escape -> {
                promptMode = null
                promptBuffer.clear()
            }

            KeyType.Backspace, KeyType.Delete -> {
                if (promptBuffer.isNotEmpty()) {
                    promptBuffer.deleteAt(promptBuffer.length - 1)
                }
            }

            KeyType.Enter -> {
                val value = promptBuffer.toString().trim()
                promptMode = null
                promptBuffer.clear()
                if (value.isNotBlank()) {
                    when (mode) {
                        PromptMode.SEARCH -> search(value)
                        PromptMode.SUBMIT -> submitIdentifier(value)
                    }
                }
            }

            KeyType.Character -> promptBuffer.append(key.character)
            else -> Unit
        }
    }

    private fun handleCommand(character: Char?) {
        when (character?.lowercaseChar()) {
            'q' -> running.set(false)
            '1' -> currentTab = Tab.NOW_PLAYING
            '2' -> currentTab = Tab.SEARCH
            '3' -> currentTab = Tab.QUEUE
            '/' -> openPrompt(PromptMode.SEARCH, Tab.SEARCH)
            'u' -> openPrompt(PromptMode.SUBMIT, currentTab)
            'a' -> submitSelectedSearchResult(TrackAddMode.NORMAL)
            'p' -> playSelectedNow()
            'r' -> requestQueue()
            'x' -> removeSelectedQueueTrack()
            ' ' -> togglePause()
            'n' -> playbackControl(PlaybackAction.SKIP)
            's' -> playbackControl(PlaybackAction.STOP)
            'c' -> reloadConfig()
            'o' -> cycleSearchSource()
            '+' -> adjustVolume(5)
            '-' -> adjustVolume(-5)
            'j' -> moveSelection(1)
            'k' -> moveSelection(-1)
        }
    }

    private fun handleMouse(mouse: MouseAction) {
        if (options.mouseMode == MouseMode.OFF) return
        val x = mouse.position.column
        val y = mouse.position.row
        when (mouse.actionType) {
            MouseActionType.SCROLL_UP -> scrollAt(x, y, -3)
            MouseActionType.SCROLL_DOWN -> scrollAt(x, y, 3)
            MouseActionType.DRAG -> updateDrag(x)
            MouseActionType.CLICK_RELEASE -> finishDrag(x)
            MouseActionType.CLICK_DOWN -> {
                activeDrag = null
                activeDragRect = null
                hitRegions.asReversed().firstOrNull { it.rect.contains(x, y) }?.onClick?.invoke(x, y)
            }

            MouseActionType.MOVE -> Unit
        }
    }

    private fun selectRelativeTab(delta: Int) {
        val tabs = Tab.entries
        val index = tabs.indexOf(currentTab).coerceAtLeast(0)
        currentTab = tabs[(index + delta).floorMod(tabs.size)]
    }

    private fun openPrompt(mode: PromptMode, tab: Tab) {
        currentTab = tab
        promptMode = mode
        promptBuffer.clear()
    }

    private fun activateSelection() {
        when (currentTab) {
            Tab.NOW_PLAYING -> togglePause()
            Tab.SEARCH -> submitSelectedSearchResult(TrackAddMode.NORMAL)
            Tab.QUEUE -> playSelectedQueueTrack()
        }
    }

    private fun moveSelection(delta: Int) {
        when (currentTab) {
            Tab.NOW_PLAYING -> Unit
            Tab.SEARCH -> {
                val size = app.client.searchResults.size
                selectedSearchIndex = if (size == 0) 0 else (selectedSearchIndex + delta).coerceIn(0, size - 1)
            }

            Tab.QUEUE -> {
                val size = app.client.queueTracks.size
                selectedQueueIndex = if (size == 0) 0 else (selectedQueueIndex + delta).coerceIn(0, size - 1)
            }
        }
    }

    private fun scrollAt(x: Int, y: Int, delta: Int) {
        when {
            lastSearchListRect?.contains(x, y) == true -> {
                currentTab = Tab.SEARCH
                moveSelection(delta)
            }

            lastQueueListRect?.contains(x, y) == true -> {
                currentTab = Tab.QUEUE
                moveSelection(delta)
            }

            else -> moveSelection(delta)
        }
    }

    private fun search(query: String) {
        app.client.setStatus("Searching...")
        app.scope.launch {
            runCatching {
                app.client.requestService.search(
                    SearchQuery(
                        query = query,
                        sourceId = currentSearchSourceId(),
                        limit = 40,
                        offset = 0,
                    ),
                )
            }.onFailure { app.client.setStatus("Search failed: ${it.message}") }
        }
    }

    private fun submitIdentifier(identifier: String) {
        app.client.setStatus("Submitting...")
        app.scope.launch {
            runCatching {
                app.client.requestService.submitIdentifier(identifier, TrackAddMode.NORMAL)
            }.onFailure { app.client.setStatus("Submit failed: ${it.message}") }
        }
    }

    private fun submitSelectedSearchResult(mode: TrackAddMode) {
        val entry = app.client.searchResults.getOrNull(selectedSearchIndex) ?: return
        submitSearchResult(entry, mode)
    }

    private fun submitSearchResult(entry: SelectionEntry, mode: TrackAddMode) {
        app.client.setStatus(if (mode == TrackAddMode.PLAY_NOW) "Playing selection..." else "Submitting selection...")
        app.scope.launch {
            runCatching {
                app.client.requestService.submitSelection(entry, mode)
            }.onFailure { app.client.setStatus("Selection failed: ${it.message}") }
        }
    }

    private fun requestQueue() {
        app.scope.launch {
            runCatching { app.client.requestService.requestQueue() }
                .onFailure { app.client.setStatus("Queue request failed: ${it.message}") }
        }
    }

    private fun removeSelectedQueueTrack() {
        val track = app.client.queueTracks.getOrNull(selectedQueueIndex) ?: return
        removeQueueTrack(track)
    }

    private fun removeQueueTrack(track: TrackInfo) {
        val sourceId = track.sourceId ?: return
        app.scope.launch {
            runCatching {
                app.client.requestService.removeQueuedTrack(sourceId, track.id)
            }.onFailure { app.client.setStatus("Remove failed: ${it.message}") }
        }
    }

    private fun playSelectedNow() {
        when (currentTab) {
            Tab.NOW_PLAYING -> togglePause()
            Tab.SEARCH -> submitSelectedSearchResult(TrackAddMode.PLAY_NOW)
            Tab.QUEUE -> playSelectedQueueTrack()
        }
    }

    private fun playSelectedQueueTrack() {
        val track = app.client.queueTracks.getOrNull(selectedQueueIndex) ?: return
        app.client.setStatus("Playing queued track...")
        app.scope.launch {
            runCatching {
                app.client.requestService.submitTrack(track, TrackAddMode.PLAY_NOW)
            }.onFailure { app.client.setStatus("Play now failed: ${it.message}") }
        }
    }

    private fun togglePause() {
        when (app.client.currentContext?.state) {
            is PlaybackState.Playing -> playbackControl(PlaybackAction.PAUSE)
            is PlaybackState.Paused -> playbackControl(PlaybackAction.RESUME)
            else -> Unit
        }
    }

    private fun playbackControl(action: PlaybackAction, positionMs: Long = 0L) {
        app.scope.launch {
            runCatching { app.client.requestService.controlPlayback(action, positionMs) }
                .onFailure { app.client.setStatus("Playback control failed: ${it.message}") }
        }
    }

    private fun adjustVolume(delta: Int) {
        val current = app.client.playbackService.configuredVolumePercent
        app.client.playbackService.setConfiguredVolumePercent(ClientVolume.normalizePercent(current + delta))
    }

    private fun setVolumeFromRatio(ratio: Float) {
        val percent = ClientVolume.gainToPercent(ratio.coerceIn(0f, 1f))
        app.client.playbackService.setConfiguredVolumePercent(percent)
    }

    private fun reloadConfig() {
        app.scope.launch {
            runCatching { app.reloadConfig() }
                .onFailure { app.client.setStatus("Reload failed: ${it.message}") }
        }
    }

    private fun cycleSearchSource() {
        val sources = searchableSources()
        if (sources.isEmpty()) {
            selectedSearchSourceId = null
            app.client.setStatus("No searchable sources")
            return
        }
        val current = currentSearchSourceId()
        val index = sources.indexOfFirst { it.id == current }.takeIf { it >= 0 } ?: -1
        val next = sources[(index + 1).floorMod(sources.size)]
        selectedSearchSourceId = next.id
        app.client.setStatus("Search source: ${next.displayName}")
    }

    private fun beginDrag(target: DragTarget, rect: Rect, x: Int) {
        activeDrag = target
        activeDragRect = rect
        updateDrag(x)
    }

    private fun updateDrag(x: Int) {
        val target = activeDrag ?: return
        val rect = activeDragRect ?: return
        val ratio = ((x - rect.x).toFloat() / rect.width.coerceAtLeast(1)).coerceIn(0f, 1f)
        when (target) {
            DragTarget.SEEK -> seekPreviewProgress = ratio
            DragTarget.VOLUME -> setVolumeFromRatio(ratio)
        }
    }

    private fun finishDrag(x: Int) {
        val target = activeDrag
        if (target != null) updateDrag(x)
        when (target) {
            DragTarget.SEEK -> {
                val ctx = app.client.currentContext
                val duration = ctx?.track?.durationMs ?: 0L
                val progress = seekPreviewProgress
                if (duration > 0 && progress != null) {
                    playbackControl(PlaybackAction.SEEK, (duration * progress).toLong().coerceIn(0L, duration))
                }
                seekPreviewProgress = null
            }

            DragTarget.VOLUME, null -> Unit
        }
        activeDrag = null
        activeDragRect = null
    }

    private fun render(screen: TerminalScreen) {
        val size = screen.terminalSize
        val width = drawableColumns(size.columns)
        val height = size.rows
        if (width <= 0 || height <= 0) return

        selectedSearchIndex = selectedSearchIndex.coerceSelection(app.client.searchResults.size)
        selectedQueueIndex = selectedQueueIndex.coerceSelection(app.client.queueTracks.size)
        lastSearchListRect = null
        lastQueueListRect = null
        nextHitRegions = mutableListOf()

        val graphics = screen.newTextGraphics()
        if (needsClear) {
            screen.clear()
            needsClear = false
        }
        clearVirtualFrame(graphics, width, height)

        val helpLines = helpLines(width)
        val promptLineCount = if (promptMode != null) 1 else 0
        val footerStart = (height - helpLines.size - promptLineCount).coerceAtLeast(2)
        val content = Rect(0, 2, width, (footerStart - 2).coerceAtLeast(0))

        drawHeader(graphics, width)
        drawTabs(graphics, width)
        drawContent(graphics, content)
        drawPromptAndFooter(graphics, width, height, footerStart, helpLines)

        hitRegions = nextHitRegions
    }

    private fun drawHeader(graphics: TextGraphics, width: Int) {
        fillRect(graphics, Rect(0, 0, width, 1), HEADER_BG)
        val volume = app.client.playbackService.effectiveVolumePercent
        val source = currentSearchSourceName().ifBlank { "no source" }
        val status = app.client.statusMessage
        val header = if (width >= 100) {
            " MoeMusic Standalone | vol $volume% | source $source | $status"
        } else {
            " MoeMusic | vol $volume% | $status"
        }
        putText(graphics, 0, 0, width, header, HEADER_FG, HEADER_BG, SGR.BOLD)
    }

    private fun drawTabs(graphics: TextGraphics, width: Int) {
        fillRect(graphics, Rect(0, 1, width, 1), TAB_BG)
        var x = 1
        Tab.entries.forEach { tab ->
            val label = " ${tab.number} ${tab.label} "
            val selected = tab == currentTab
            val fg = if (selected) TAB_SELECTED_FG else TAB_FG
            val bg = if (selected) TAB_SELECTED_BG else TAB_BG
            val rect = Rect(x, 1, label.length.coerceAtMost((width - x).coerceAtLeast(0)), 1)
            if (rect.width > 0) {
                putText(graphics, x, 1, rect.width, label, fg, bg, if (selected) SGR.BOLD else null)
                addHit(rect) { currentTab = tab }
            }
            x += label.length + 1
        }
    }

    private fun drawContent(graphics: TextGraphics, content: Rect) {
        if (content.height <= 0 || content.width <= 0) return

        if (content.width >= WIDE_LAYOUT_MIN_WIDTH && content.height >= 14) {
            val nowWidth = (content.width * 36 / 100).coerceIn(34, 48)
            val nowRect = Rect(content.x, content.y, nowWidth, content.height)
            val mainRect = Rect(content.x + nowWidth, content.y, content.width - nowWidth, content.height)
            drawNowPlayingPane(graphics, nowRect, compact = false)
            drawActiveTab(graphics, mainRect)
            return
        }

        if (currentTab != Tab.NOW_PLAYING && content.height >= 10) {
            val miniHeight = 4
            drawMiniNowPlaying(graphics, Rect(content.x, content.y, content.width, miniHeight))
            drawActiveTab(graphics, Rect(content.x, content.y + miniHeight, content.width, content.height - miniHeight))
        } else {
            drawActiveTab(graphics, content)
        }
    }

    private fun drawActiveTab(graphics: TextGraphics, rect: Rect) {
        when (currentTab) {
            Tab.NOW_PLAYING -> drawNowPlayingPane(graphics, rect, compact = rect.width < 74)
            Tab.SEARCH -> drawSearchPane(graphics, rect)
            Tab.QUEUE -> drawQueuePane(graphics, rect)
        }
    }

    private fun drawMiniNowPlaying(graphics: TextGraphics, rect: Rect) {
        fillRect(graphics, rect, PANEL_BG)
        val ctx = app.client.currentContext
        val state = playbackStateLabel(ctx?.state)
        putText(graphics, rect.x + 1, rect.y, rect.width - 2, "Now Playing [$state]", ACCENT, PANEL_BG, SGR.BOLD)
        if (ctx == null) {
            putText(graphics, rect.x + 1, rect.y + 1, rect.width - 2, "No track loaded.", MUTED, PANEL_BG)
            return
        }
        val title = ctx.track.title.ifBlank { ctx.track.id }
        val artist = ctx.track.artistDisplay.ifBlank { "-" }
        putText(graphics, rect.x + 1, rect.y + 1, rect.width - 2, title, TEXT, PANEL_BG, SGR.BOLD)
        putText(graphics, rect.x + 1, rect.y + 2, rect.width - 2, artist, MUTED, PANEL_BG)
        drawPlaybackProgress(graphics, Rect(rect.x + 1, rect.y + 3, rect.width - 2, 1), interactive = false)
    }

    private fun drawNowPlayingPane(graphics: TextGraphics, rect: Rect, compact: Boolean) {
        drawPanel(graphics, rect, "Now Playing")
        val body = rect.inset(1)
        if (body.width <= 0 || body.height <= 0) return

        val ctx = app.client.currentContext
        val state = playbackStateLabel(ctx?.state)
        val volume = app.client.playbackService.effectiveVolumePercent
        putText(graphics, body.x, body.y, body.width, "State: $state   Volume: $volume%", MUTED, PANEL_BG)

        if (ctx == null) {
            putText(graphics, body.x, body.y + 2, body.width, "No track loaded.", MUTED, PANEL_BG)
            drawVolumeBar(graphics, Rect(body.x, body.bottom - 2, body.width, 1), interactive = true)
            return
        }

        val track = ctx.track
        val coverWidth = coverWidthFor(body, compact)
        val hasCover = coverWidth > 0 && options.coverMode != CoverMode.OFF
        var textX = body.x
        var textY = body.y + 2
        var textWidth = body.width
        if (hasCover) {
            val coverHeight = coverWidth / 2
            val coverX = if (body.width >= 70) body.x else body.x + ((body.width - coverWidth) / 2).coerceAtLeast(0)
            val coverY = body.y + 2
            coverRenderer.draw(graphics, track, coverX, coverY, coverWidth, coverHeight, options.coverMode)
            if (body.width >= 70) {
                textX = coverX + coverWidth + 2
                textY = coverY
                textWidth = (body.right - textX + 1).coerceAtLeast(0)
            } else {
                textY = coverY + coverHeight + 1
            }
        }

        putText(graphics, textX, textY, textWidth, track.title.ifBlank { track.id }, TEXT, PANEL_BG, SGR.BOLD)
        putText(graphics, textX, textY + 1, textWidth, track.artistDisplay.ifBlank { "-" }, MUTED, PANEL_BG)
        putText(graphics, textX, textY + 2, textWidth, "Album: ${track.album ?: "-"}", WARM, PANEL_BG)
        putText(graphics, textX, textY + 3, textWidth, "Source: ${sourceDisplayName(track.sourceId).ifBlank { "-" }}", SOURCE, PANEL_BG)
        val position = app.client.currentPositionMs(ctx)
        putText(graphics, textX, textY + 4, textWidth, "${formatTime(position)} / ${formatTime(track.durationMs)}", MUTED, PANEL_BG)

        app.client.currentLyricLine()?.let { lyric ->
            val lyricY = (body.bottom - 5).coerceAtLeast(textY + 6)
            if (lyricY < body.bottom - 3) {
                putText(graphics, body.x, lyricY, body.width, lyric, LYRIC, PANEL_BG)
            }
        }

        drawVolumeBar(graphics, Rect(body.x, body.bottom - 3, body.width, 1), interactive = true)
        drawPlaybackProgress(graphics, Rect(body.x, body.bottom - 2, body.width, 1), interactive = true)
        drawPlaybackButtons(graphics, Rect(body.x, body.bottom, body.width, 1))
    }

    private fun drawSearchPane(graphics: TextGraphics, rect: Rect) {
        drawPanel(graphics, rect, "Search")
        val body = rect.inset(1)
        if (body.height <= 0 || body.width <= 0) return

        val sourceLabel = "Source: ${currentSearchSourceName().ifBlank { "none" }}"
        val sourceRect = Rect(body.x, body.y, minOf(body.width, maxOf(18, columnWidth(sourceLabel) + 2)), 1)
        putText(graphics, sourceRect.x, sourceRect.y, sourceRect.width, sourceLabel, SOURCE, BUTTON_BG, SGR.BOLD)
        addHit(sourceRect) { cycleSearchSource() }

        val hintX = sourceRect.x + sourceRect.width + 2
        val hint = if (body.width >= 78) {
            "/ search   Enter/Add queues selected   p play now   o source"
        } else {
            "/ search  Enter add  p now"
        }
        putText(graphics, hintX, body.y, (body.right - hintX + 1).coerceAtLeast(0), hint, MUTED, PANEL_BG)

        app.client.searchFailure?.let {
            putText(graphics, body.x, body.y + 1, body.width, it, ERROR, PANEL_BG)
        } ?: putText(
            graphics,
            body.x,
            body.y + 1,
            body.width,
            "Query: ${app.client.searchQuery.ifBlank { "-" }}   Results: ${app.client.searchResults.size}",
            MUTED,
            PANEL_BG,
        )

        val listRect = Rect(body.x, body.y + 3, body.width, (body.height - 3).coerceAtLeast(0))
        lastSearchListRect = listRect
        drawSearchRows(graphics, listRect)
    }

    private fun drawQueuePane(graphics: TextGraphics, rect: Rect) {
        drawPanel(graphics, rect, "Queue")
        val body = rect.inset(1)
        if (body.height <= 0 || body.width <= 0) return

        val refreshRect = Rect(body.x, body.y, minOf(12, body.width), 1)
        putText(graphics, refreshRect.x, refreshRect.y, refreshRect.width, " Refresh ", TEXT, BUTTON_BG, SGR.BOLD)
        addHit(refreshRect) { requestQueue() }
        val countText = "Queue: ${app.client.queueTracks.size}"
        putText(graphics, refreshRect.x + refreshRect.width + 2, body.y, body.width - refreshRect.width - 2, countText, MUTED, PANEL_BG)
        app.client.queueFailure?.let {
            putText(graphics, body.x, body.y + 1, body.width, it, ERROR, PANEL_BG)
        }

        var listY = body.y + 2
        app.client.currentContext?.track?.let { track ->
            drawPinnedCurrentTrack(graphics, Rect(body.x, listY, body.width, 2), track)
            listY += 3
        }

        val listRect = Rect(body.x, listY, body.width, (body.bottom - listY + 1).coerceAtLeast(0))
        lastQueueListRect = listRect
        drawQueueRows(graphics, listRect)
    }

    private fun drawSearchRows(graphics: TextGraphics, rect: Rect) {
        val results = app.client.searchResults
        if (rect.height <= 0) return
        if (results.isEmpty()) {
            putText(graphics, rect.x, rect.y, rect.width, "No search results yet. Press / to search.", MUTED, PANEL_BG)
            return
        }

        val visibleRows = (rect.height / SEARCH_ROW_HEIGHT).coerceAtLeast(1)
        val start = scrollStart(selectedSearchIndex, visibleRows, results.size)
        val actionWidth = if (rect.width >= 70) 16 else 8
        val textWidth = (rect.width - actionWidth - 1).coerceAtLeast(10)

        results.drop(start).take(visibleRows).forEachIndexed { index, entry ->
            val actualIndex = start + index
            val row = Rect(rect.x, rect.y + index * SEARCH_ROW_HEIGHT, rect.width, SEARCH_ROW_HEIGHT)
            val selected = actualIndex == selectedSearchIndex
            fillRect(graphics, row, if (selected) SELECTED_BG else if (index % 2 == 0) ROW_BG else ROW_ALT_BG)
            addHit(row) {
                currentTab = Tab.SEARCH
                selectedSearchIndex = actualIndex
            }

            val title = "${actualIndex + 1}. ${entry.title.ifBlank { entry.selectionId }}"
            val duration = formatTime(entry.durationMs)
            putText(graphics, row.x + 1, row.y, textWidth - 1, "$title  $duration", if (selected) SELECTED_FG else TEXT, rowBg(selected, index), SGR.BOLD)
            val meta = listOfNotNull(
                entry.artistDisplay.takeIf { it.isNotBlank() && it != "-" },
                entry.album?.takeIf { it.isNotBlank() },
                entry.unavailableReason?.debugString()?.takeIf { it.isNotBlank() },
            ).joinToString(" | ").ifBlank { "-" }
            putText(graphics, row.x + 1, row.y + 1, textWidth - 1, meta, if (entry.unavailableReason == null) MUTED else ERROR, rowBg(selected, index))

            val addRect = Rect(row.right - actionWidth + 1, row.y, minOf(7, actionWidth), 1)
            putText(graphics, addRect.x, addRect.y, addRect.width, " Add ", TEXT, BUTTON_BG, SGR.BOLD)
            addHit(addRect) {
                selectedSearchIndex = actualIndex
                submitSearchResult(entry, TrackAddMode.NORMAL)
            }
            if (rect.width >= 70) {
                val nowRect = Rect(addRect.right + 1, row.y, 7, 1)
                putText(graphics, nowRect.x, nowRect.y, nowRect.width, " Now ", TEXT, BUTTON_BG, SGR.BOLD)
                addHit(nowRect) {
                    selectedSearchIndex = actualIndex
                    submitSearchResult(entry, TrackAddMode.PLAY_NOW)
                }
            }
        }
    }

    private fun drawQueueRows(graphics: TextGraphics, rect: Rect) {
        val tracks = app.client.queueTracks
        if (rect.height <= 0) return
        if (tracks.isEmpty()) {
            putText(graphics, rect.x, rect.y, rect.width, "Queue is empty.", MUTED, PANEL_BG)
            return
        }

        val visibleRows = (rect.height / QUEUE_ROW_HEIGHT).coerceAtLeast(1)
        val start = scrollStart(selectedQueueIndex, visibleRows, tracks.size)
        val actionWidth = if (rect.width >= 70) 16 else 8
        val textWidth = (rect.width - actionWidth - 1).coerceAtLeast(10)

        tracks.drop(start).take(visibleRows).forEachIndexed { index, track ->
            val actualIndex = start + index
            val row = Rect(rect.x, rect.y + index * QUEUE_ROW_HEIGHT, rect.width, QUEUE_ROW_HEIGHT)
            val selected = actualIndex == selectedQueueIndex
            fillRect(graphics, row, if (selected) SELECTED_BG else if (index % 2 == 0) ROW_BG else ROW_ALT_BG)
            addHit(row) {
                currentTab = Tab.QUEUE
                selectedQueueIndex = actualIndex
            }

            val title = "${actualIndex + 1}. ${track.title.ifBlank { track.id }}"
            putText(graphics, row.x + 1, row.y, textWidth - 1, title, if (selected) SELECTED_FG else TEXT, rowBg(selected, index), SGR.BOLD)
            val meta = listOfNotNull(
                track.artistDisplay.takeIf { it.isNotBlank() && it != "-" },
                track.submittedByUserName?.takeIf { it.isNotBlank() }?.let { "@$it" },
                sourceDisplayName(track.sourceId).takeIf { it.isNotBlank() },
            ).joinToString(" | ").ifBlank { "-" }
            putText(graphics, row.x + 1, row.y + 1, textWidth - 1, meta, MUTED, rowBg(selected, index))

            val playRect = Rect(row.right - actionWidth + 1, row.y, minOf(7, actionWidth), 1)
            putText(graphics, playRect.x, playRect.y, playRect.width, " Play ", TEXT, BUTTON_BG, SGR.BOLD)
            addHit(playRect) {
                selectedQueueIndex = actualIndex
                playSelectedQueueTrack()
            }
            if (rect.width >= 70) {
                val delRect = Rect(playRect.right + 1, row.y, 7, 1)
                putText(graphics, delRect.x, delRect.y, delRect.width, " Del ", TEXT, DANGER_BG, SGR.BOLD)
                addHit(delRect) {
                    selectedQueueIndex = actualIndex
                    removeQueueTrack(track)
                }
            }
        }
    }

    private fun drawPinnedCurrentTrack(graphics: TextGraphics, rect: Rect, track: TrackInfo) {
        fillRect(graphics, rect, PINNED_BG)
        putText(graphics, rect.x + 1, rect.y, rect.width - 2, "Playing: ${track.title.ifBlank { track.id }}", WARM, PINNED_BG, SGR.BOLD)
        putText(graphics, rect.x + 1, rect.y + 1, rect.width - 2, track.artistDisplay.ifBlank { "-" }, MUTED, PINNED_BG)
    }

    private fun drawVolumeBar(graphics: TextGraphics, rect: Rect, interactive: Boolean) {
        if (rect.width <= 0 || rect.height <= 0) return
        val percent = app.client.playbackService.effectiveVolumePercent
        val label = "Vol $percent%"
        drawBar(graphics, rect, percent / 100f, VOLUME_BG, VOLUME_FG, label)
        if (interactive) {
            addHitAt(rect) { x, _ -> beginDrag(DragTarget.VOLUME, rect, x) }
        }
    }

    private fun drawPlaybackProgress(graphics: TextGraphics, rect: Rect, interactive: Boolean) {
        if (rect.width <= 0 || rect.height <= 0) return
        val ctx = app.client.currentContext
        val duration = ctx?.track?.durationMs ?: 0L
        val position = ctx?.let(app.client::currentPositionMs) ?: 0L
        val progress = seekPreviewProgress ?: if (duration > 0) {
            (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        val label = "${formatTime(position)} / ${formatTime(duration)}"
        drawBar(graphics, rect, progress, BAR_BG, if (ctx?.state is PlaybackState.Paused) PAUSED else ACCENT, label)
        if (interactive && duration > 0) {
            addHitAt(rect) { x, _ -> beginDrag(DragTarget.SEEK, rect, x) }
        }
    }

    private fun drawPlaybackButtons(graphics: TextGraphics, rect: Rect) {
        if (rect.width <= 0) return
        val ctx = app.client.currentContext
        val playLabel = if (ctx?.state is PlaybackState.Playing) " Pause " else " Resume "
        var x = rect.x
        val buttons = listOf(
            playLabel to { togglePause() },
            " Skip " to { playbackControl(PlaybackAction.SKIP) },
            " Stop " to { playbackControl(PlaybackAction.STOP) },
        )
        buttons.forEach { (label, action) ->
            val button = Rect(x, rect.y, label.length.coerceAtMost((rect.right - x + 1).coerceAtLeast(0)), 1)
            if (button.width > 0) {
                putText(graphics, button.x, button.y, button.width, label, TEXT, BUTTON_BG, SGR.BOLD)
                addHit(button, action)
            }
            x += label.length + 1
        }
    }

    private fun drawPromptAndFooter(
        graphics: TextGraphics,
        width: Int,
        height: Int,
        footerStart: Int,
        helpLines: List<String>,
    ) {
        val prompt = promptMode
        if (prompt != null) {
            putText(
                graphics,
                0,
                footerStart,
                width,
                "${prompt.label}: $promptBuffer",
                PROMPT,
                FOOTER_BG,
                SGR.BOLD,
            )
        }
        helpLines.forEachIndexed { index, line ->
            putText(graphics, 0, height - helpLines.size + index, width, line, FOOTER_FG, FOOTER_BG)
        }
    }

    private fun drawPanel(graphics: TextGraphics, rect: Rect, title: String) {
        fillRect(graphics, rect, PANEL_BG)
        if (rect.width <= 0 || rect.height <= 0) return
        val active = title == currentTab.label || title == "Now Playing" && currentTab == Tab.NOW_PLAYING
        putText(
            graphics,
            rect.x,
            rect.y,
            rect.width,
            " $title ",
            if (active) TAB_SELECTED_FG else ACCENT,
            if (active) TAB_SELECTED_BG else PANEL_TITLE_BG,
            SGR.BOLD,
        )
    }

    private fun drawBar(
        graphics: TextGraphics,
        rect: Rect,
        progress: Float,
        background: TextColor,
        foreground: TextColor,
        label: String,
    ) {
        val filled = (rect.width * progress.coerceIn(0f, 1f)).roundToInt().coerceIn(0, rect.width)
        fillRect(graphics, rect, background)
        if (filled > 0) {
            fillRect(graphics, Rect(rect.x, rect.y, filled, rect.height), foreground)
        }
        val labelText = " $label "
        val labelWidth = columnWidth(labelText)
        val labelX = rect.x + ((rect.width - labelWidth) / 2).coerceAtLeast(0)
        putText(graphics, labelX, rect.y, minOf(labelWidth, rect.right - labelX + 1), labelText, TEXT, TextColor.ANSI.DEFAULT)
    }

    private fun fillRect(graphics: TextGraphics, rect: Rect, background: TextColor) {
        if (rect.width <= 0 || rect.height <= 0) return
        val character = TextCharacter(' ', TEXT, background)
        for (row in 0 until rect.height) {
            for (col in 0 until rect.width) {
                graphics.setCharacter(rect.x + col, rect.y + row, character)
            }
        }
    }

    private fun putText(
        graphics: TextGraphics,
        x: Int,
        y: Int,
        width: Int,
        text: String,
        color: TextColor,
        background: TextColor = TextColor.ANSI.DEFAULT,
        modifier: SGR? = null,
    ) {
        if (width <= 0 || y < 0) return
        graphics.foregroundColor = color
        graphics.backgroundColor = background
        val safe = fitAndPadColumns(text, width)
        if (modifier == null) {
            graphics.putString(x, y, safe)
        } else {
            graphics.putString(x, y, safe, modifier)
        }
    }

    private fun fitAndPadColumns(text: String, width: Int): String {
        if (width <= 0) return ""
        val fitted = TerminalTextUtils.fitString(text.sanitizeForTerminal(), width)
        val padding = (width - columnWidth(fitted)).coerceAtLeast(0)
        return fitted + " ".repeat(padding)
    }

    private fun columnWidth(text: String): Int =
        TerminalTextUtils.getColumnWidth(text.sanitizeForTerminal())

    private fun String.sanitizeForTerminal(): String =
        replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')

    private fun clearVirtualFrame(graphics: TextGraphics, width: Int, height: Int) {
        fillRect(graphics, Rect(0, 0, width, height), TextColor.ANSI.DEFAULT)
    }

    private fun addHit(rect: Rect, action: () -> Unit) {
        if (rect.width > 0 && rect.height > 0) {
            nextHitRegions += HitRegion(rect) { _, _ -> action() }
        }
    }

    private fun addHitAt(rect: Rect, action: (Int, Int) -> Unit) {
        if (rect.width > 0 && rect.height > 0) {
            nextHitRegions += HitRegion(rect, action)
        }
    }

    private fun rowBg(selected: Boolean, index: Int): TextColor =
        if (selected) SELECTED_BG else if (index % 2 == 0) ROW_BG else ROW_ALT_BG

    private fun coverWidthFor(rect: Rect, compact: Boolean): Int {
        if (options.coverMode == CoverMode.OFF) return 0
        if (rect.height < 14 || rect.width < 28) return 0
        val maxByHeight = ((rect.height - 8).coerceAtLeast(0)) * 2
        val maxByWidth = if (compact || rect.width < 70) rect.width - 4 else rect.width / 3
        return minOf(30, maxByHeight, maxByWidth).coerceAtLeast(0).let { width ->
            if (width >= 12) width else 0
        }
    }

    private fun searchableSources() =
        app.client.sourceCatalog?.sources?.filter { it.searchable }.orEmpty()

    private fun currentSearchSourceId(): String? {
        val sources = searchableSources()
        if (sources.isEmpty()) {
            selectedSearchSourceId = null
            return null
        }
        val validIds = sources.mapTo(linkedSetOf()) { it.id }
        selectedSearchSourceId = sequenceOf(
            selectedSearchSourceId,
            app.client.sourceCatalog?.defaultSourceId?.takeIf { it.isNotBlank() },
            sources.firstOrNull()?.id,
        ).filterNotNull().firstOrNull { it in validIds }
        return selectedSearchSourceId
    }

    private fun currentSearchSourceName(): String =
        currentSearchSourceId()?.let(::sourceDisplayName).orEmpty()

    private fun sourceDisplayName(sourceId: String?): String {
        val id = sourceId?.takeIf { it.isNotBlank() } ?: return ""
        return app.client.sourceCatalog?.sources?.firstOrNull { it.id == id }?.displayName ?: id
    }

    private fun playbackStateLabel(state: PlaybackState?): String =
        when (state) {
            is PlaybackState.Playing -> "playing"
            is PlaybackState.Paused -> "paused"
            PlaybackState.Stopped, null -> "stopped"
        }

    private fun helpLines(width: Int): List<String> {
        val commands = listOf(
            "1/2/3 tabs",
            "/ search",
            "u URL",
            "enter add/play",
            "p play now",
            "space pause",
            "n skip",
            "s stop",
            "+/- volume",
            "o source",
            "r refresh",
            "x remove",
            "j/k move",
            "c reload",
            "q quit",
        )
        return fitHelpCommands(commands, width, maxHelpLines(width))
    }

    private fun maxHelpLines(width: Int): Int =
        when {
            width >= 96 -> 2
            width >= 52 -> 3
            else -> 4
        }

    private fun fitHelpCommands(commands: List<String>, width: Int, maxLines: Int): List<String> {
        if (width <= 0 || maxLines <= 0) return emptyList()
        val mandatory = setOf("1/2/3 tabs", "/ search", "enter add/play", "space pause", "q quit")
        val fittedCommands = commands.toMutableList()
        var lines = packHelpCommands(fittedCommands, width)
        while (lines.size > maxLines && fittedCommands.any { it !in mandatory }) {
            val index = fittedCommands.indexOfLast { it !in mandatory }
            fittedCommands.removeAt(index)
            lines = packHelpCommands(fittedCommands, width)
        }
        return lines.take(maxLines)
    }

    private fun packHelpCommands(commands: List<String>, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        commands.forEach { command ->
            val candidate = if (line.isEmpty()) command else "$line | $command"
            if (line.isNotEmpty() && TerminalTextUtils.getColumnWidth(candidate) > width) {
                lines += TerminalTextUtils.fitString(line, width)
                line = command
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) {
            lines += TerminalTextUtils.fitString(line, width)
        }
        return lines
    }

    private fun scrollStart(index: Int, visibleRows: Int, total: Int): Int {
        if (visibleRows <= 0 || total <= visibleRows) return 0
        return index.coerceIn(0, total - 1)
            .let { selected -> (selected - visibleRows + 1).coerceAtLeast(0).coerceAtMost(total - visibleRows) }
    }

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "--:--"
        val seconds = ms / 1_000L
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun Int.floorMod(divisor: Int): Int =
        ((this % divisor) + divisor) % divisor

    private fun Int.coerceSelection(size: Int): Int =
        if (size <= 0) 0 else coerceIn(0, size - 1)

    private fun drawableColumns(terminalColumns: Int): Int =
        (terminalColumns - LAST_COLUMN_WRAP_GUARD).coerceAtLeast(0)

    companion object {
        fun createTerminal(terminalMode: TerminalMode, mouseMode: MouseMode = MouseMode.AUTO): Terminal {
            val textFactory = newTerminalFactory(mouseMode).setForceTextTerminal(true)
            return when (terminalMode) {
                TerminalMode.TEXT -> createTextTerminal(textFactory)
                TerminalMode.SWING -> newTerminalFactory(mouseMode).createTerminalEmulator()
                TerminalMode.AUTO -> runCatching {
                    createTextTerminal(textFactory)
                }.getOrElse { textError ->
                    if (isAwtHeadless()) {
                        throw terminalUnavailable(textError)
                    }
                    newTerminalFactory(mouseMode).createTerminalEmulator()
                }
            }
        }

        private fun createTextTerminal(factory: DefaultTerminalFactory): Terminal =
            try {
                factory.createTerminal()
            } catch (e: IOException) {
                throw terminalUnavailable(e)
            }

        private fun newTerminalFactory(mouseMode: MouseMode): DefaultTerminalFactory =
            DefaultTerminalFactory()
                .setInputTimeout(50)
                .setTerminalEmulatorTitle("MoeMusic Standalone")
                .apply {
                    if (mouseMode != MouseMode.OFF) {
                        setMouseCaptureMode(MouseCaptureMode.CLICK_RELEASE_DRAG)
                    }
                }

        private fun terminalUnavailable(cause: Throwable): StandaloneTerminalException =
            StandaloneTerminalException(
                buildString {
                    appendLine("MoeMusic standalone could not open a text terminal.")
                    appendLine("Lanterna needs a controlling TTY for its Unix backend, but this process has none.")
                    appendLine()
                    appendLine("Recommended:")
                    appendLine("  ../shared/gradlew -p . installDist")
                    appendLine("  ./build/install/moemusic-standalone/bin/moemusic-standalone")
                    appendLine()
                    appendLine("Alternatives:")
                    appendLine("  Use --terminal swing on desktop systems.")
                    appendLine("  Use --terminal text only when running from a real terminal, not a detached Gradle/IDE process.")
                    append("Cause: ")
                    append(cause.message ?: cause.javaClass.simpleName)
                },
                cause,
            )

        private fun isAwtHeadless(): Boolean =
            runCatching { GraphicsEnvironment.isHeadless() }.getOrDefault(true)

        private const val WIDE_LAYOUT_MIN_WIDTH = 112
        private const val SEARCH_ROW_HEIGHT = 2
        private const val QUEUE_ROW_HEIGHT = 2
        private const val LAST_COLUMN_WRAP_GUARD = 0 // 1

        private val TEXT = TextColor.RGB(234, 237, 243)
        private val MUTED = TextColor.RGB(158, 166, 178)
        private val HEADER_FG = TextColor.RGB(246, 248, 252)
        private val HEADER_BG = TextColor.RGB(30, 37, 49)
        private val FOOTER_FG = TextColor.RGB(190, 198, 210)
        private val FOOTER_BG = TextColor.RGB(24, 28, 35)
        private val TAB_FG = TextColor.RGB(180, 190, 204)
        private val TAB_BG = TextColor.RGB(18, 20, 26)
        private val TAB_SELECTED_FG = TextColor.RGB(255, 255, 255)
        private val TAB_SELECTED_BG = TextColor.RGB(39, 83, 105)
        private val PANEL_BG = TextColor.RGB(15, 17, 22)
        private val PANEL_TITLE_BG = TextColor.RGB(25, 29, 38)
        private val ROW_BG = TextColor.RGB(22, 25, 32)
        private val ROW_ALT_BG = TextColor.RGB(27, 31, 40)
        private val SELECTED_BG = TextColor.RGB(49, 62, 82)
        private val SELECTED_FG = TextColor.RGB(255, 255, 255)
        private val PINNED_BG = TextColor.RGB(46, 39, 20)
        private val BUTTON_BG = TextColor.RGB(38, 70, 83)
        private val DANGER_BG = TextColor.RGB(100, 44, 50)
        private val BAR_BG = TextColor.RGB(51, 57, 70)
        private val VOLUME_BG = TextColor.RGB(40, 49, 64)
        private val VOLUME_FG = TextColor.RGB(86, 160, 211)
        private val ACCENT = TextColor.RGB(61, 185, 129)
        private val PAUSED = TextColor.RGB(229, 190, 92)
        private val WARM = TextColor.RGB(222, 184, 100)
        private val SOURCE = TextColor.RGB(97, 201, 176)
        private val LYRIC = TextColor.RGB(125, 218, 149)
        private val ERROR = TextColor.RGB(245, 104, 104)
        private val PROMPT = TextColor.RGB(135, 213, 168)
    }
}
