package org.lolicode.moemusic.terminal

import com.googlecode.lanterna.TerminalSize
import com.googlecode.lanterna.terminal.Terminal
import com.googlecode.lanterna.terminal.ansi.ANSITerminal
import com.googlecode.lanterna.input.BasicCharacterPattern
import com.googlecode.lanterna.input.CharacterPattern
import com.googlecode.lanterna.input.KeyDecodingProfile
import com.googlecode.lanterna.input.KeyStroke
import com.googlecode.lanterna.input.KeyType
import org.jline.terminal.TerminalBuilder
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

internal interface TerminalCompatibilityInfo {
    val terminalCharset: Charset

    fun supportsUnicodeGlyph(character: Char): Boolean =
        terminalCharset.newEncoder().canEncode(character)

    fun startupStatusMessage(): String? =
        if (terminalCharset == Charsets.UTF_8) {
            null
        } else {
            "Console charset ${terminalCharset.displayName()} detected; some Unicode text and cover glyphs may degrade. Enable UTF-8 for full rendering."
        }
}

internal class JLineTextTerminal private constructor(
    private val jlineTerminal: org.jline.terminal.Terminal,
    mouseMode: MouseMode,
) : ANSITerminal(
        jlineTerminal.input(),
        jlineTerminal.output(),
        jlineTerminal.encoding(),
    ), TerminalCompatibilityInfo {

    override val terminalCharset: Charset = jlineTerminal.encoding()

    private val closed = AtomicBoolean(false)
    private val previousWinchHandler = jlineTerminal.handle(org.jline.terminal.Terminal.Signal.WINCH) { refreshSize() }

    init {
        jlineTerminal.enterRawMode()
        getInputDecoder().addProfile(WindowsEnterKeyDecodingProfile)
        jlineTerminal.trackMouse(
            if (mouseMode == MouseMode.OFF) {
                org.jline.terminal.Terminal.MouseTracking.Off
            } else {
                org.jline.terminal.Terminal.MouseTracking.Button
            },
        )
        jlineTerminal.resume()
        refreshSize()
    }

    override fun findTerminalSize(): TerminalSize =
        currentSize().also(::onResized)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            super.close()
        } finally {
            runCatching {
                jlineTerminal.handle(org.jline.terminal.Terminal.Signal.WINCH, previousWinchHandler)
            }
            jlineTerminal.close()
        }
    }

    private fun refreshSize() {
        onResized(currentSize())
    }

    private fun currentSize(): TerminalSize {
        val size = jlineTerminal.size
        return TerminalSize(
            size.columns.coerceAtLeast(1),
            size.rows.coerceAtLeast(1),
        )
    }

    companion object {
        fun open(mouseMode: MouseMode): Terminal =
            JLineTextTerminal(
                TerminalBuilder.builder()
                    .name("MoeMusic Terminal")
                    .system(true)
                    .dumb(false)
                    .nativeSignals(true)
                    .paused(true)
                    .build(),
                mouseMode,
            )

        private object WindowsEnterKeyDecodingProfile : KeyDecodingProfile {
            private val patterns = listOf<CharacterPattern>(
                BasicCharacterPattern(KeyStroke(KeyType.Enter), '\r'),
            )

            override fun getPatterns(): Collection<CharacterPattern> = patterns
        }
    }
}
