package org.lolicode.moemusic.terminal

import java.awt.image.BufferedImage

internal object TerminalSixelEncoder {
    fun encode(image: BufferedImage): String {
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val rows = (height + SIXEL_ROW_HEIGHT - 1) / SIXEL_ROW_HEIGHT
        val pixels = IntArray(width * height)
        val colors = colorHistogram(image, width, height, pixels)
        val palette = buildPalette(colors)
        val indexedPixels = IntArray(width * height)
        val usedColors = BooleanArray(palette.size)
        val rowColors = Array(rows) { BooleanArray(palette.size) }
        val colorMatchCache = HashMap<Int, Int>(colors.size)

        for (y in 0 until height) {
            val row = y / SIXEL_ROW_HEIGHT
            for (x in 0 until width) {
                val index = y * width + x
                val color = pixels[index]
                val colorIndex = colorMatchCache.getOrPut(color) { nearestColorIndex(color, palette) }
                indexedPixels[index] = colorIndex
                usedColors[colorIndex] = true
                rowColors[row][colorIndex] = true
            }
        }

        return buildString(width * rows) {
            append("\"1;1;")
            append(width)
            append(';')
            append(height)

            for (colorIndex in palette.indices) {
                if (usedColors[colorIndex]) {
                    appendPaletteColor(colorIndex, palette)
                }
            }

            for (row in 0 until rows) {
                appendSixelRow(row, width, height, palette.size, indexedPixels, rowColors[row])
                if (row != rows - 1) {
                    append('-')
                }
            }
        }
    }

    private fun colorHistogram(
        image: BufferedImage,
        width: Int,
        height: Int,
        pixels: IntArray,
    ): Collection<ColorStats> {
        val colors = HashMap<Int, ColorStats>()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rgb = image.getRGB(x, y) and RGB_MASK
                pixels[y * width + x] = rgb
                colors.getOrPut(reducedColorKey(rgb)) { ColorStats() }.add(rgb)
            }
        }
        return colors.values
    }

    private fun buildPalette(colors: Collection<ColorStats>): List<Rgb> {
        if (colors.isEmpty()) return listOf(Rgb(0, 0, 0))
        if (colors.size <= MAX_PALETTE_COLORS) {
            return colors.sortedByDescending { it.count }.map { it.averageColor() }
        }

        val buckets = mutableListOf(ColorBucket(colors.toList()))
        while (buckets.size < MAX_PALETTE_COLORS) {
            val bucket = buckets
                .withIndex()
                .filter { it.value.colors.size > 1 }
                .maxWithOrNull(compareBy({ it.value.range * it.value.count }, { it.value.count }))
                ?: break
            val split = bucket.value.split() ?: break
            buckets.removeAt(bucket.index)
            buckets += split.first
            buckets += split.second
        }

        return buckets.sortedByDescending { it.count }.map { it.averageColor() }
    }

    private fun nearestColorIndex(color: Int, palette: List<Rgb>): Int {
        val red = (color ushr 16) and 0xFF
        val green = (color ushr 8) and 0xFF
        val blue = color and 0xFF
        var bestIndex = 0
        var bestDistance = Int.MAX_VALUE
        for (index in palette.indices) {
            val paletteColor = palette[index]
            val distance = squaredDistance(red, green, blue, paletteColor)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return bestIndex
    }

    private fun squaredDistance(red: Int, green: Int, blue: Int, color: Rgb): Int {
        val redDiff = red - color.red
        val greenDiff = green - color.green
        val blueDiff = blue - color.blue
        return redDiff * redDiff + greenDiff * greenDiff + blueDiff * blueDiff
    }

    private fun reducedColorKey(color: Int): Int {
        val red = ((color ushr 16) and 0xFF) ushr REDUCED_COLOR_SHIFT
        val green = ((color ushr 8) and 0xFF) ushr REDUCED_COLOR_SHIFT
        val blue = (color and 0xFF) ushr REDUCED_COLOR_SHIFT
        return (red shl (REDUCED_COLOR_BITS * 2)) or (green shl REDUCED_COLOR_BITS) or blue
    }

    private fun StringBuilder.appendPaletteColor(colorIndex: Int, palette: List<Rgb>) {
        val color = palette[colorIndex]
        append('#')
        append(colorIndex)
        append(";2;")
        append(sixelComponent(color.red))
        append(';')
        append(sixelComponent(color.green))
        append(';')
        append(sixelComponent(color.blue))
    }

    private fun StringBuilder.appendSixelRow(
        row: Int,
        width: Int,
        height: Int,
        colorCount: Int,
        indexedPixels: IntArray,
        usedInRow: BooleanArray,
    ) {
        for (colorIndex in 0 until colorCount) {
            if (!usedInRow[colorIndex]) continue

            append('$')
            append('#')
            append(colorIndex)

            var previousData = -1
            var previousCount = 0
            for (x in 0 until width) {
                var bits = 0
                for (offsetY in 0 until SIXEL_ROW_HEIGHT) {
                    val y = row * SIXEL_ROW_HEIGHT + offsetY
                    if (y < height && indexedPixels[y * width + x] == colorIndex) {
                        bits = bits or (1 shl offsetY)
                    }
                }
                val data = bits + SIXEL_DATA_OFFSET
                if (data == previousData) {
                    previousCount += 1
                } else {
                    appendRun(previousData, previousCount)
                    previousData = data
                    previousCount = 1
                }
            }
            appendRun(previousData, previousCount)
        }
    }

    private fun StringBuilder.appendRun(data: Int, count: Int) {
        if (count <= 0) return
        if (count == 1) {
            append(data.toChar())
            return
        }
        append('!')
        append(count)
        append(data.toChar())
    }

    private fun sixelComponent(component: Int): Int =
        ((component.coerceIn(0, 255) * 100) + 127) / 255

    private class ColorStats {
        var count = 0
            private set
        private var redSum = 0L
        private var greenSum = 0L
        private var blueSum = 0L

        fun add(color: Int) {
            count += 1
            redSum += (color ushr 16) and 0xFF
            greenSum += (color ushr 8) and 0xFF
            blueSum += color and 0xFF
        }

        fun averageColor(): Rgb =
            Rgb(
                red = average(redSum),
                green = average(greenSum),
                blue = average(blueSum),
            )

        private fun average(sum: Long): Int =
            ((sum + count / 2L) / count).toInt()
    }

    private class ColorBucket(
        val colors: List<ColorStats>,
    ) {
        val count: Int = colors.sumOf { it.count }

        private val averages = colors.map { it.averageColor() }
        private val redRange = componentRange { it.red }
        private val greenRange = componentRange { it.green }
        private val blueRange = componentRange { it.blue }
        val range = maxOf(redRange, greenRange, blueRange)

        fun averageColor(): Rgb {
            var redSum = 0L
            var greenSum = 0L
            var blueSum = 0L
            for (index in colors.indices) {
                val color = averages[index]
                val colorCount = colors[index].count
                redSum += color.red.toLong() * colorCount
                greenSum += color.green.toLong() * colorCount
                blueSum += color.blue.toLong() * colorCount
            }
            return Rgb(
                red = weightedAverage(redSum),
                green = weightedAverage(greenSum),
                blue = weightedAverage(blueSum),
            )
        }

        fun split(): Pair<ColorBucket, ColorBucket>? {
            if (colors.size <= 1) return null
            val channel = when (range) {
                redRange -> Channel.RED
                greenRange -> Channel.GREEN
                else -> Channel.BLUE
            }
            val sorted = colors.sortedBy { channel.component(it.averageColor()) }
            val splitIndex = weightedMedianIndex(sorted)
            return ColorBucket(sorted.subList(0, splitIndex)) to ColorBucket(sorted.subList(splitIndex, sorted.size))
        }

        private fun componentRange(component: (Rgb) -> Int): Int =
            component(averages.maxBy(component)) - component(averages.minBy(component))

        private fun weightedAverage(sum: Long): Int =
            ((sum + count / 2L) / count).toInt()

        private fun weightedMedianIndex(sorted: List<ColorStats>): Int {
            val half = count / 2
            var cumulative = 0
            for (index in sorted.indices) {
                cumulative += sorted[index].count
                if (cumulative >= half) {
                    return (index + 1).coerceIn(1, sorted.lastIndex)
                }
            }
            return (sorted.size / 2).coerceIn(1, sorted.lastIndex)
        }
    }

    private enum class Channel {
        RED,
        GREEN,
        BLUE,
        ;

        fun component(color: Rgb): Int =
            when (this) {
                RED -> color.red
                GREEN -> color.green
                BLUE -> color.blue
            }
    }

    private data class Rgb(
        val red: Int,
        val green: Int,
        val blue: Int,
    )

    private const val MAX_PALETTE_COLORS = 256
    private const val RGB_MASK = 0x00FFFFFF
    private const val REDUCED_COLOR_BITS = 5
    private const val REDUCED_COLOR_SHIFT = 8 - REDUCED_COLOR_BITS
    private const val SIXEL_ROW_HEIGHT = 6
    private const val SIXEL_DATA_OFFSET = 63
}
