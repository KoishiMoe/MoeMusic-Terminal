package org.lolicode.moemusic.standalone

import com.googlecode.lanterna.TerminalTextUtils
import com.googlecode.lanterna.TextCharacter
import com.googlecode.lanterna.TextColor
import com.googlecode.lanterna.graphics.TextGraphics
import com.googlecode.lanterna.terminal.Terminal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.clientcore.media.ClientMediaFirewall
import org.lolicode.moemusic.clientcore.media.computeCoverDecodeDownscaleFactor
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.media.MediaUrlPolicyResult
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLConnection
import java.util.Base64
import javax.imageio.ImageIO
import javax.imageio.ImageReader

internal class StandaloneCoverArtRenderer(
    private val scope: CoroutineScope,
) {
    private sealed interface CoverState {
        data object Loading : CoverState
        data object Failed : CoverState
        data class Ready(val cells: List<List<TextCharacter>>, val png: ByteArray) : CoverState
    }

    private data class CoverLimits(
        val maxDownloadBytes: Int,
        val maxSourceDimension: Int,
        val maxSourcePixels: Long,
        val maxDecodeDownscaleFactor: Int,
        val decodeTargetSize: Int,
    )

    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, CoverState>(MAX_CACHE_ENTRIES, 0.75f, true)
    private val terminalImageIds = LinkedHashMap<String, Int>()
    private var nextTerminalImageId = TERMINAL_IMAGE_ID_BASE
    private var frameProtocol: TerminalImageProtocol? = null
    private var requestedTerminalImage: TerminalImageRequest? = null
    private var renderedTerminalImage: TerminalImageRequest? = null
    private var terminalImageInvalidated = false

    fun beginFrame(terminal: Terminal, coverMode: CoverMode) {
        frameProtocol = TerminalImageProtocol.select(terminal, coverMode)
        requestedTerminalImage = null
    }

    fun draw(
        graphics: TextGraphics,
        track: TrackInfo?,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        coverMode: CoverMode,
    ) {
        if (width < MIN_COVER_WIDTH || height < MIN_COVER_HEIGHT || coverMode == CoverMode.OFF) {
            return
        }

        val url = track?.coverUrl?.takeIf { it.isNotBlank() }
        if (url == null) {
            drawPlaceholder(graphics, x, y, width, height, "no cover")
            return
        }

        val key = "$url#$width.$height"
        when (val state = stateFor(key)) {
            null -> {
                remember(key, CoverState.Loading)
                loadAsync(key, url, width, height)
                drawPlaceholder(graphics, x, y, width, height, "loading")
            }

            CoverState.Loading -> drawPlaceholder(graphics, x, y, width, height, "loading")
            CoverState.Failed -> drawPlaceholder(graphics, x, y, width, height, "cover failed")
            is CoverState.Ready -> {
                val protocol = frameProtocol
                if (protocol != null && coverMode != CoverMode.UNICODE) {
                    drawPlaceholder(graphics, x, y, width, height, "")
                    requestedTerminalImage = TerminalImageRequest(
                        protocol = protocol,
                        imageId = terminalImageIdFor(key),
                        x = x,
                        y = y,
                        width = width,
                        height = height,
                        png = state.png,
                    )
                } else {
                    drawCells(graphics, x, y, width, height, state.cells)
                }
            }
        }
    }

    fun invalidateTerminalGraphics() {
        terminalImageInvalidated = true
    }

    fun flushTerminalGraphics(terminal: Terminal) {
        val request = requestedTerminalImage
        val rendered = renderedTerminalImage
        if (request == null) {
            if (rendered != null) {
                deleteTerminalImage(terminal, rendered)
                renderedTerminalImage = null
            }
            return
        }

        if (!terminalImageInvalidated && rendered != null && rendered.samePlacementAs(request)) {
            return
        }

        if (rendered != null && rendered.imageId != request.imageId) {
            deleteTerminalImage(terminal, rendered)
        }
        writeTerminalImage(terminal, request)
        renderedTerminalImage = request
        terminalImageInvalidated = false
    }

    fun clearTerminalGraphics(terminal: Terminal) {
        renderedTerminalImage?.let { deleteTerminalImage(terminal, it) }
        renderedTerminalImage = null
        requestedTerminalImage = null
    }

    private fun stateFor(key: String): CoverState? =
        synchronized(cacheLock) { cache[key] }

    private fun remember(key: String, state: CoverState) {
        synchronized(cacheLock) {
            cache[key] = state
            while (cache.size > MAX_CACHE_ENTRIES) {
                val iterator = cache.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun loadAsync(key: String, url: String, width: Int, height: Int) {
        scope.launch {
            val state = runCatching {
                when (ClientMediaFirewall.evaluate(url)) {
                    MediaUrlPolicyResult.Allow -> Unit
                    is MediaUrlPolicyResult.Reject -> error("Blocked by local media policy")
                }
                val limits = coverLimits(width, height)
                val source = loadCoverImage(url, limits)
                CoverState.Ready(
                    cells = renderCells(source, width, height),
                    png = encodePng(source),
                )
            }.getOrElse {
                CoverState.Failed
            }
            remember(key, state)
        }
    }

    private fun coverLimits(width: Int, height: Int): CoverLimits {
        val config = ModConfigManager.config.client.coverArt.normalized()
        return CoverLimits(
            maxDownloadBytes = config.maxDownloadMebibytes * 1024 * 1024,
            maxSourceDimension = config.maxSourceDimension,
            maxSourcePixels = config.maxSourcePixels,
            maxDecodeDownscaleFactor = config.maxDecodeDownscaleFactor,
            decodeTargetSize = minOf(config.maxTextureSize, maxOf(width * 12, height * 24, 128)),
        )
    }

    private fun loadCoverImage(url: String, limits: CoverLimits): BufferedImage {
        val connection = openCoverConnection(url)
        val bytes = try {
            val contentLength = connection.contentLengthLong
            if (contentLength > limits.maxDownloadBytes) {
                error("Image download too large: $contentLength bytes")
            }
            connection.getInputStream().use { stream -> readAllBytesLimited(stream, limits.maxDownloadBytes) }
        } finally {
            if (connection is HttpURLConnection) {
                connection.disconnect()
            }
        }
        return decodeCoverBytes(bytes, limits)
    }

    private fun openCoverConnection(url: String): URLConnection {
        val connection = URI(url).toURL().openConnection()
        connection.connectTimeout = COVER_CONNECT_TIMEOUT_MS
        connection.readTimeout = COVER_READ_TIMEOUT_MS
        if (connection is HttpURLConnection) {
            connection.instanceFollowRedirects = true
        }
        return connection
    }

    private fun decodeCoverBytes(bytes: ByteArray, limits: CoverLimits): BufferedImage =
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { imageStream ->
            val reader = ImageIO.getImageReaders(imageStream).asSequence().firstOrNull()
                ?: error("Unsupported or corrupted image format")
            reader.useImage(imageStream) {
                val sourceWidth = reader.getWidth(0)
                val sourceHeight = reader.getHeight(0)
                if (sourceWidth <= 0 || sourceHeight <= 0) {
                    error("Image has invalid dimensions: ${sourceWidth}x$sourceHeight")
                }
                if (sourceWidth > limits.maxSourceDimension || sourceHeight > limits.maxSourceDimension) {
                    error("Image dimensions exceed cap: ${sourceWidth}x$sourceHeight")
                }
                if (sourceWidth.toLong() * sourceHeight.toLong() > limits.maxSourcePixels) {
                    error("Image pixel count exceeds cap: ${sourceWidth}x$sourceHeight")
                }

                val squareSize = minOf(sourceWidth, sourceHeight).coerceAtLeast(1)
                val startX = ((sourceWidth - squareSize) / 2).coerceAtLeast(0)
                val startY = ((sourceHeight - squareSize) / 2).coerceAtLeast(0)
                val downscaleFactor = computeCoverDecodeDownscaleFactor(
                    sourceWidth = squareSize,
                    sourceHeight = squareSize,
                    maxTextureSize = limits.decodeTargetSize,
                    maxDecodeDownscaleFactor = limits.maxDecodeDownscaleFactor,
                )
                val readParam = reader.defaultReadParam.apply {
                    sourceRegion = Rectangle(startX, startY, squareSize, squareSize)
                    if (downscaleFactor > 1) {
                        setSourceSubsampling(downscaleFactor, downscaleFactor, 0, 0)
                    }
                }
                reader.read(0, readParam) ?: error("Unsupported or corrupted image format")
            }
        }

    private inline fun <T> ImageReader.useImage(
        imageStream: Any,
        block: () -> T,
    ): T {
        try {
            input = imageStream
            return block()
        } finally {
            dispose()
        }
    }

    private fun renderCells(source: BufferedImage, width: Int, height: Int): List<List<TextCharacter>> {
        val targetHeight = height * 2
        val scaled = BufferedImage(width, targetHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.background = java.awt.Color(18, 18, 22)
            graphics.clearRect(0, 0, width, targetHeight)
            graphics.drawImage(source, 0, 0, width, targetHeight, null)
        } finally {
            graphics.dispose()
        }
        return List(height) { row ->
            List(width) { col ->
                val top = scaled.getRGB(col, row * 2)
                val bottom = scaled.getRGB(col, row * 2 + 1)
                TextCharacter(UPPER_HALF_BLOCK, rgb(top), rgb(bottom))
            }
        }
    }

    private fun encodePng(source: BufferedImage): ByteArray =
        ByteArrayOutputStream().use { output ->
            if (!ImageIO.write(source, "png", output)) {
                error("No PNG writer available")
            }
            output.toByteArray()
        }

    private fun drawCells(
        graphics: TextGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        cells: List<List<TextCharacter>>,
    ) {
        for (row in 0 until height) {
            val line = cells.getOrNull(row) ?: continue
            for (col in 0 until width) {
                graphics.setCharacter(x + col, y + row, line.getOrNull(col) ?: continue)
            }
        }
    }

    private fun drawPlaceholder(
        graphics: TextGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        label: String,
    ) {
        val blank = TextCharacter(' ', PLACEHOLDER_FG, PLACEHOLDER_BG)
        for (row in 0 until height) {
            for (col in 0 until width) {
                graphics.setCharacter(x + col, y + row, blank)
            }
        }
        val text = TerminalTextUtils.fitString(label, width)
        val textX = x + ((width - TerminalTextUtils.getColumnWidth(text)) / 2).coerceAtLeast(0)
        val textY = y + height / 2
        graphics.foregroundColor = PLACEHOLDER_FG
        graphics.backgroundColor = PLACEHOLDER_BG
        if (text.isNotEmpty()) {
            graphics.putString(textX, textY, text)
        }
    }

    private fun terminalImageIdFor(key: String): Int =
        terminalImageIds.getOrPut(key) {
            nextTerminalImageId += 1
            nextTerminalImageId
        }

    private fun writeTerminalImage(terminal: Terminal, request: TerminalImageRequest) {
        when (request.protocol) {
            TerminalImageProtocol.KITTY -> writeKittyImage(terminal, request)
        }
    }

    private fun deleteTerminalImage(terminal: Terminal, request: TerminalImageRequest) {
        when (request.protocol) {
            TerminalImageProtocol.KITTY -> {
                TerminalRawWriter.write(terminal, "\u001B_Ga=d,d=i,i=${request.imageId},q=2;\u001B\\")
                terminal.flush()
            }
        }
    }

    private fun writeKittyImage(terminal: Terminal, request: TerminalImageRequest) {
        val encoded = Base64.getEncoder().encodeToString(request.png)
        terminal.setCursorPosition(request.x, request.y)
        var offset = 0
        var firstChunk = true
        while (offset < encoded.length) {
            val nextOffset = minOf(offset + KITTY_CHUNK_SIZE, encoded.length)
            val moreChunks = nextOffset < encoded.length
            val chunk = encoded.substring(offset, nextOffset)
            val params = if (firstChunk) {
                "a=T,f=100,t=d,i=${request.imageId},c=${request.width},r=${request.height},C=1,q=2,m=${if (moreChunks) 1 else 0}"
            } else {
                "m=${if (moreChunks) 1 else 0}"
            }
            TerminalRawWriter.write(terminal, "\u001B_G$params;$chunk\u001B\\")
            offset = nextOffset
            firstChunk = false
        }
        terminal.flush()
    }

    private fun readAllBytesLimited(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(8_192)
        val output = ByteArrayOutputStream()
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) {
                error("Image download exceeded $limit bytes")
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun rgb(argb: Int): TextColor.RGB =
        TextColor.RGB(
            (argb ushr 16) and 0xFF,
            (argb ushr 8) and 0xFF,
            argb and 0xFF,
        )

    private companion object {
        private const val MIN_COVER_WIDTH = 12
        private const val MIN_COVER_HEIGHT = 6
        private const val MAX_CACHE_ENTRIES = 32
        private const val COVER_CONNECT_TIMEOUT_MS = 5_000
        private const val COVER_READ_TIMEOUT_MS = 5_000
        private const val TERMINAL_IMAGE_ID_BASE = 4000
        private const val KITTY_CHUNK_SIZE = 4096
        private const val UPPER_HALF_BLOCK = '\u2580'
        private val PLACEHOLDER_FG = TextColor.RGB(150, 156, 166)
        private val PLACEHOLDER_BG = TextColor.RGB(31, 34, 42)
    }
}

private data class TerminalImageRequest(
    val protocol: TerminalImageProtocol,
    val imageId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val png: ByteArray,
) {
    fun samePlacementAs(other: TerminalImageRequest): Boolean =
        protocol == other.protocol &&
            imageId == other.imageId &&
            x == other.x &&
            y == other.y &&
            width == other.width &&
            height == other.height &&
            png.contentEquals(other.png)
}

private enum class TerminalImageProtocol {
    KITTY,
    ;

    companion object {
        fun select(terminal: Terminal, coverMode: CoverMode): TerminalImageProtocol? {
            if (coverMode == CoverMode.OFF || coverMode == CoverMode.UNICODE) return null
            if (!TerminalRawWriter.canWrite(terminal)) return null
            if (!isTextTerminal(terminal)) return null
            return when (coverMode) {
                CoverMode.KITTY -> KITTY
                CoverMode.TERMINAL -> detect()
                CoverMode.AUTO -> detect().takeIf { isKnownSupportedEnvironment() }
                CoverMode.UNICODE, CoverMode.OFF -> null
            }
        }

        private fun detect(): TerminalImageProtocol? =
            if (isKnownSupportedEnvironment()) KITTY else null

        private fun isKnownSupportedEnvironment(): Boolean {
            val env = System.getenv()
            val term = env["TERM"].orEmpty().lowercase()
            val termProgram = env["TERM_PROGRAM"].orEmpty().lowercase()
            return "kitty" in term ||
                termProgram in setOf("kitty", "wezterm", "ghostty") ||
                "wezterm" in termProgram ||
                "ghostty" in termProgram
        }

        private fun isTextTerminal(terminal: Terminal): Boolean {
            val className = terminal.javaClass.name.lowercase()
            return ".swing." !in className && ".awt" !in className
        }
    }
}

private object TerminalRawWriter {
    fun canWrite(terminal: Terminal): Boolean =
        findRawTarget(terminal, mutableSetOf()) != null

    fun write(terminal: Terminal, text: String): Boolean =
        write(terminal, text.toByteArray(Charsets.US_ASCII))

    private fun write(terminal: Terminal, bytes: ByteArray): Boolean {
        val target = findRawTarget(terminal, mutableSetOf()) ?: return false
        return runCatching {
            target.method.isAccessible = true
            target.method.invoke(target.instance, bytes as Any)
            true
        }.getOrDefault(false)
    }

    private data class RawTarget(
        val instance: Any,
        val method: Method,
    )

    private fun findRawTarget(instance: Any, seen: MutableSet<Any>): RawTarget? {
        if (!seen.add(instance)) return null
        findWriteToTerminal(instance.javaClass)?.let { return RawTarget(instance, it) }

        var type: Class<*>? = instance.javaClass
        while (type != null) {
            type.declaredFields.forEach { field ->
                if (Terminal::class.java.isAssignableFrom(field.type)) {
                    val nested = runCatching {
                        field.isAccessible = true
                        field.get(instance)
                    }.getOrNull()
                    if (nested != null) {
                        findRawTarget(nested, seen)?.let { return it }
                    }
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun findWriteToTerminal(start: Class<*>): Method? {
        var type: Class<*>? = start
        while (type != null) {
            val method = type.declaredMethods.firstOrNull { method ->
                method.name == "writeToTerminal" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == ByteArray::class.java
            }
            if (method != null) return method
            type = type.superclass
        }
        return null
    }
}
