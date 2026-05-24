package org.lolicode.moemusic.standalone

import com.googlecode.lanterna.SGR
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import com.googlecode.lanterna.screen.Screen
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.googlecode.lanterna.terminal.Terminal
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
import java.awt.GraphicsEnvironment
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class StandaloneTui(
    private val app: StandaloneApplication,
    private val terminal: Terminal,
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
    private var needsClear = true
    private var forceCompleteRefresh = true

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
                    handleKey(key)
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
        if (width <= 0 || height <= 0) return
        val graphics = screen.newTextGraphics()
        if (needsClear) {
            screen.clear()
            needsClear = false
        }
        clearVirtualFrame(graphics, width, height)

        var y = 0
        putLine(graphics, y++, width, "MoeMusic Standalone", TextColor.ANSI.CYAN, SGR.BOLD)
        putLine(graphics, y++, width, "Config: ${app.configDir}", TextColor.ANSI.WHITE)
        putLine(graphics, y++, width, "Status: ${app.client.statusMessage}", TextColor.ANSI.YELLOW)
        y++

        y = drawNowPlaying(graphics, y, width)
        y++

        val helpLines = helpLines(width)
        val promptLineCount = if (promptMode != null) 1 else 0
        val footerStart = (height - helpLines.size - promptLineCount).coerceAtLeast(0)
        val contentRows = (footerStart - y).coerceAtLeast(0)
        val panelRows = ((contentRows - 2) / 2).coerceAtLeast(0)
        if (contentRows >= 2) {
            y = drawSearch(graphics, y, width, panelRows)
            y = drawQueue(graphics, y, width, panelRows)
        }
        while (y < footerStart) {
            putLine(graphics, y++, width, "", TextColor.ANSI.WHITE)
        }

        val prompt = promptMode
        if (prompt != null) {
            putLine(
                graphics,
                footerStart,
                width,
                "${prompt.label}: $promptBuffer",
                TextColor.ANSI.GREEN,
                SGR.BOLD,
            )
        }
        helpLines.forEachIndexed { index, line ->
            putLine(graphics, height - helpLines.size + index, width, line, TextColor.ANSI.WHITE)
        }
    }

    private fun clearVirtualFrame(
        graphics: com.googlecode.lanterna.graphics.TextGraphics,
        width: Int,
        height: Int,
    ) {
        graphics.foregroundColor = TextColor.ANSI.WHITE
        val blank = " ".repeat(width)
        for (row in 0 until height) {
            graphics.putString(0, row, blank)
        }
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

    private fun helpLines(width: Int): List<String> =
        when {
            width < 48 -> listOf(
                "q quit  / search  u URL  enter add",
                "space pause  n skip  s stop  +/- vol",
                "tab focus  r queue  x rm  c reload",
            )

            width < 88 -> listOf(
                "q quit | / search | u URL | enter/a add | tab focus | r queue | x remove",
                "space pause | n skip | s stop | +/- volume | c reload",
            )

            else -> listOf(
                "q quit | / search | u submit URL | enter/a add | r queue | x remove",
                "space pause | n skip | s stop | +/- volume | c reload | tab focus",
            )
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

    companion object {
        fun createTerminal(terminalMode: TerminalMode): Terminal {
            val textFactory = newTerminalFactory().setForceTextTerminal(true)
            return when (terminalMode) {
                TerminalMode.TEXT -> createTextTerminal(textFactory)
                TerminalMode.SWING -> newTerminalFactory().createTerminalEmulator()
                TerminalMode.AUTO -> runCatching {
                    createTextTerminal(textFactory)
                }.getOrElse { textError ->
                    if (isAwtHeadless()) {
                        throw terminalUnavailable(textError)
                    }
                    newTerminalFactory().createTerminalEmulator()
                }
            }
        }

        private fun createTextTerminal(factory: DefaultTerminalFactory): Terminal =
            try {
                factory.createTerminal()
            } catch (e: IOException) {
                throw terminalUnavailable(e)
            }

        private fun newTerminalFactory(): DefaultTerminalFactory =
            DefaultTerminalFactory()
                .setInputTimeout(50)
                .setTerminalEmulatorTitle("MoeMusic Standalone")

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
    }
}
