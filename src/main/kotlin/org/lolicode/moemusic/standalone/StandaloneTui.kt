package org.lolicode.moemusic.standalone

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import kotlinx.coroutines.launch
import org.lolicode.moemusic.api.model.PlaybackState
import org.lolicode.moemusic.api.model.SearchQuery
import org.lolicode.moemusic.api.model.SelectionEntry
import org.lolicode.moemusic.api.model.TrackAddMode
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.api.model.artistDisplay
import org.lolicode.moemusic.api.service.PlaybackAction
import org.lolicode.moemusic.core.config.ClientVolume
import org.lolicode.moemusic.core.config.ModConfigManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class StandaloneTui(
    private val app: StandaloneApplication,
) {

    private enum class Focus {
        SEARCH,
        QUEUE,
    }

    private enum class PromptMode(
        val label: String,
    ) {
        SEARCH("Search"),
        SUBMIT("Submit URL or identifier"),
    }

    private val running = AtomicBoolean(true)
    private var focus = Focus.SEARCH
    private var selectedSearchIndex = 0
    private var selectedQueueIndex = 0
    private var promptMode: PromptMode? = null
    private val promptBuffer = StringBuilder()

    fun run() {
        val terminal = DefaultTerminalFactory().createTerminal()
        val screen = TerminalScreen(terminal)
        screen.startScreen()
        screen.cursorPosition = null
        requestQueue()

        try {
            while (running.get()) {
                var key = screen.pollInput()
                while (key != null) {
                    handleKey(key)
                    key = screen.pollInput()
                }
                render(screen)
                screen.refresh()
                Thread.sleep(100)
            }
        } finally {
            screen.stopScreen()
        }
    }

    private fun handleKey(key: KeyStroke) {
        val mode = promptMode
        if (mode != null) {
            handlePromptKey(mode, key)
            return
        }

        when (key.keyType) {
            KeyType.Escape -> running.set(false)
            KeyType.Tab -> focus = if (focus == Focus.SEARCH) Focus.QUEUE else Focus.SEARCH
            KeyType.ArrowUp -> moveSelection(-1)
            KeyType.ArrowDown -> moveSelection(1)
            KeyType.Enter -> submitSelectedSearchResult()
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
            '/' -> openPrompt(PromptMode.SEARCH)
            'u' -> openPrompt(PromptMode.SUBMIT)
            'a' -> submitSelectedSearchResult()
            'r' -> requestQueue()
            'x' -> removeSelectedQueueTrack()
            ' ' -> togglePause()
            'n' -> playbackControl(PlaybackAction.SKIP)
            's' -> playbackControl(PlaybackAction.STOP)
            'c' -> reloadConfig()
            '+' -> adjustVolume(5)
            '-' -> adjustVolume(-5)
            'j' -> moveSelection(1)
            'k' -> moveSelection(-1)
        }
    }

    private fun openPrompt(mode: PromptMode) {
        promptMode = mode
        promptBuffer.clear()
    }

    private fun moveSelection(delta: Int) {
        when (focus) {
            Focus.SEARCH -> {
                val size = app.client.searchResults.size
                selectedSearchIndex = if (size == 0) 0 else (selectedSearchIndex + delta).coerceIn(0, size - 1)
            }

            Focus.QUEUE -> {
                val size = app.client.queueTracks.size
                selectedQueueIndex = if (size == 0) 0 else (selectedQueueIndex + delta).coerceIn(0, size - 1)
            }
        }
    }

    private fun search(query: String) {
        app.client.setStatus("Searching...")
        app.scope.launch {
            runCatching {
                app.client.requestService.search(
                    SearchQuery(
                        query = query,
                        sourceId = app.client.sourceCatalog?.defaultSourceId?.takeIf { it.isNotBlank() },
                        limit = 20,
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

    private fun submitSelectedSearchResult() {
        val entry = app.client.searchResults.getOrNull(selectedSearchIndex) ?: return
        app.client.setStatus("Submitting selection...")
        app.scope.launch {
            runCatching {
                app.client.requestService.submitSelection(entry, TrackAddMode.NORMAL)
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
        val sourceId = track.sourceId ?: return
        app.scope.launch {
            runCatching {
                app.client.requestService.removeQueuedTrack(sourceId, track.id)
            }.onFailure { app.client.setStatus("Remove failed: ${it.message}") }
        }
    }

    private fun togglePause() {
        when (app.client.currentContext?.state) {
            is PlaybackState.Playing -> playbackControl(PlaybackAction.PAUSE)
            is PlaybackState.Paused -> playbackControl(PlaybackAction.RESUME)
            else -> Unit
        }
    }

    private fun playbackControl(action: PlaybackAction) {
        app.scope.launch {
            runCatching { app.client.requestService.controlPlayback(action) }
                .onFailure { app.client.setStatus("Playback control failed: ${it.message}") }
        }
    }

    private fun adjustVolume(delta: Int) {
        val current = app.client.playbackService.configuredVolumePercent
        app.client.playbackService.setConfiguredVolumePercent(ClientVolume.normalizePercent(current + delta))
    }

    private fun reloadConfig() {
        app.scope.launch {
            runCatching { app.reloadConfig() }
                .onFailure { app.client.setStatus("Reload failed: ${it.message}") }
        }
    }

    private fun render(screen: TerminalScreen) {
        val size = screen.terminalSize
        val width = size.columns
        val height = size.rows
        val graphics = screen.newTextGraphics()
        screen.clear()

        var y = 0
        putLine(graphics, y++, width, "MoeMusic Standalone", TextColor.ANSI.CYAN, SGR.BOLD)
        putLine(graphics, y++, width, "Config: ${app.configDir}", TextColor.ANSI.WHITE)
        putLine(graphics, y++, width, "Status: ${app.client.statusMessage}", TextColor.ANSI.YELLOW)
        y++

        y = drawNowPlaying(graphics, y, width)
        y++

        val panelHeight = max(3, (height - y - 5) / 2)
        y = drawSearch(graphics, y, width, panelHeight)
        y = drawQueue(graphics, y, width, panelHeight)

        val prompt = promptMode
        if (prompt != null) {
            putLine(graphics, height - 2, width, "${prompt.label}: $promptBuffer", TextColor.ANSI.GREEN, SGR.BOLD)
        }
        putLine(
            graphics,
            height - 1,
            width,
            "q quit | / search | u submit URL | enter/a add | r queue | x remove | space pause | n skip | s stop | +/- volume | c reload | tab focus",
            TextColor.ANSI.WHITE,
        )
    }

    private fun drawNowPlaying(
        graphics: com.googlecode.lanterna.graphics.TextGraphics,
        startY: Int,
        width: Int,
    ): Int {
        var y = startY
        val ctx = app.client.currentContext
        val state = ctx?.state?.let {
            when (it) {
                is PlaybackState.Playing -> "playing"
                is PlaybackState.Paused -> "paused"
                PlaybackState.Stopped -> "stopped"
            }
        } ?: "stopped"
        val volume = app.client.playbackService.effectiveVolumePercent
        putLine(graphics, y++, width, "Now Playing [$state] volume=$volume%", TextColor.ANSI.CYAN, SGR.BOLD)
        if (ctx == null) {
            putLine(graphics, y++, width, "No track loaded.", TextColor.ANSI.WHITE)
            return y
        }

        val position = app.client.currentPositionMs(ctx)
        putLine(graphics, y++, width, ctx.track.title.ifBlank { ctx.track.id }, TextColor.ANSI.WHITE, SGR.BOLD)
        putLine(graphics, y++, width, ctx.track.artistDisplay.ifBlank { "-" }, TextColor.ANSI.WHITE)
        putLine(graphics, y++, width, "${formatTime(position)} / ${formatTime(ctx.track.durationMs)}", TextColor.ANSI.WHITE)
        app.client.currentLyricLine()?.let { lyric ->
            putLine(graphics, y++, width, lyric, TextColor.ANSI.GREEN)
        }
        return y
    }

    private fun drawSearch(
        graphics: com.googlecode.lanterna.graphics.TextGraphics,
        startY: Int,
        width: Int,
        panelHeight: Int,
    ): Int {
        var y = startY
        val marker = if (focus == Focus.SEARCH) ">" else " "
        putLine(graphics, y++, width, "$marker Search Results (${app.client.searchResults.size})", TextColor.ANSI.CYAN, SGR.BOLD)
        app.client.searchFailure?.let {
            putLine(graphics, y++, width, it, TextColor.ANSI.RED)
        }
        val rows = panelHeight.coerceAtMost(app.client.searchResults.size)
        val start = scrollStart(selectedSearchIndex, rows, app.client.searchResults.size)
        app.client.searchResults.drop(start).take(rows).forEachIndexed { index, entry ->
            val actualIndex = start + index
            putLine(
                graphics,
                y++,
                width,
                "${if (actualIndex == selectedSearchIndex) "*" else " "} ${formatSelection(entry)}",
                if (actualIndex == selectedSearchIndex && focus == Focus.SEARCH) TextColor.ANSI.YELLOW else TextColor.ANSI.WHITE,
            )
        }
        return y + (panelHeight - rows).coerceAtLeast(0)
    }

    private fun drawQueue(
        graphics: com.googlecode.lanterna.graphics.TextGraphics,
        startY: Int,
        width: Int,
        panelHeight: Int,
    ): Int {
        var y = startY
        val marker = if (focus == Focus.QUEUE) ">" else " "
        putLine(graphics, y++, width, "$marker Queue (${app.client.queueTracks.size})", TextColor.ANSI.CYAN, SGR.BOLD)
        app.client.queueFailure?.let {
            putLine(graphics, y++, width, it, TextColor.ANSI.RED)
        }
        val rows = panelHeight.coerceAtMost(app.client.queueTracks.size)
        val start = scrollStart(selectedQueueIndex, rows, app.client.queueTracks.size)
        app.client.queueTracks.drop(start).take(rows).forEachIndexed { index, track ->
            val actualIndex = start + index
            putLine(
                graphics,
                y++,
                width,
                "${if (actualIndex == selectedQueueIndex) "*" else " "} ${formatTrack(track)}",
                if (actualIndex == selectedQueueIndex && focus == Focus.QUEUE) TextColor.ANSI.YELLOW else TextColor.ANSI.WHITE,
            )
        }
        return y + (panelHeight - rows).coerceAtLeast(0)
    }

    private fun putLine(
        graphics: com.googlecode.lanterna.graphics.TextGraphics,
        y: Int,
        width: Int,
        text: String,
        color: TextColor,
        vararg modifiers: SGR,
    ) {
        if (y < 0) return
        graphics.foregroundColor = color
        val line = text.take(width).padEnd(width)
        if (modifiers.isEmpty()) {
            graphics.putString(0, y, line)
        } else {
            graphics.putString(0, y, line, modifiers.toList())
        }
    }

    private fun scrollStart(index: Int, visibleRows: Int, total: Int): Int {
        if (visibleRows <= 0 || total <= visibleRows) return 0
        return index.coerceIn(0, total - 1)
            .let { selected -> (selected - visibleRows + 1).coerceAtLeast(0).coerceAtMost(total - visibleRows) }
    }

    private fun formatSelection(entry: SelectionEntry): String =
        "${entry.title.ifBlank { entry.selectionId }} - ${entry.artistDisplay.ifBlank { "-" }} (${formatTime(entry.durationMs)})"

    private fun formatTrack(track: TrackInfo): String =
        "${track.title.ifBlank { track.id }} - ${track.artistDisplay.ifBlank { "-" }} (${formatTime(track.durationMs)})"

    private fun formatTime(ms: Long): String {
        if (ms <= 0) return "--:--"
        val seconds = ms / 1_000L
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
