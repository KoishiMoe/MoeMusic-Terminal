package org.lolicode.moemusic.terminal

import java.awt.image.BufferedImage

internal object TerminalSixelEncoder {
    private val palette = buildList {
        for (red in 0..5) {
            for (green in 0..5) {
                for (blue in 0..5) {
                    add(
                        Rgb(
                            red = red * 255 / 5,
                            green = green * 255 / 5,
                            blue = blue * 255 / 5,
                        ),
                    )
                }
            }
        }
    }

    fun encode(image: BufferedImage): String {
        val width = image.width.coerceAtLeast(1)
        val height = image.height.coerceAtLeast(1)
        val rows = (height + SIXEL_ROW_HEIGHT - 1) / SIXEL_ROW_HEIGHT
        val indexedPixels = IntArray(width * height)
        val usedColors = BooleanArray(palette.size)
        val rowColors = Array(rows) { BooleanArray(palette.size) }

        for (y in 0 until height) {
            val row = y / SIXEL_ROW_HEIGHT
            for (x in 0 until width) {
                val colorIndex = colorIndex(image.getRGB(x, y))
                indexedPixels[y * width + x] = colorIndex
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
                    appendPaletteColor(colorIndex)
                }
            }

            for (row in 0 until rows) {
                appendSixelRow(row, width, height, indexedPixels, rowColors[row])
                if (row != rows - 1) {
                    append('-')
                }
            }
        }
    }

    private fun StringBuilder.appendPaletteColor(colorIndex: Int) {
        val color = palette[colorIndex]
        append('#')
        append(colorIndex)
        append(";2;")
        append(color.red * 100 / 255)
        append(';')
        append(color.green * 100 / 255)
        append(';')
        append(color.blue * 100 / 255)
    }

    private fun StringBuilder.appendSixelRow(
        row: Int,
        width: Int,
        height: Int,
        indexedPixels: IntArray,
        usedInRow: BooleanArray,
    ) {
        for (colorIndex in palette.indices) {
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

    private fun colorIndex(argb: Int): Int {
        val red = quantize((argb ushr 16) and 0xFF)
        val green = quantize((argb ushr 8) and 0xFF)
        val blue = quantize(argb and 0xFF)
        return red * 36 + green * 6 + blue
    }

    private fun quantize(component: Int): Int =
        ((component.coerceIn(0, 255) * 5) + 127) / 255

    private data class Rgb(
        val red: Int,
        val green: Int,
        val blue: Int,
    )

    private const val SIXEL_ROW_HEIGHT = 6
    private const val SIXEL_DATA_OFFSET = 63
}
