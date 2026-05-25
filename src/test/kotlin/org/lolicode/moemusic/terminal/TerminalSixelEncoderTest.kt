package org.lolicode.moemusic.terminal

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerminalSixelEncoderTest {
    @Test
    fun `cover mode parser accepts sixel`() {
        assertEquals(CoverMode.SIXEL, CoverMode.parse("sixel"))
    }

    @Test
    fun `windows terminal environment is sixel capable`() {
        assertTrue(isKnownSixelEnvironment(mapOf("WT_SESSION" to "{session-id}")))
    }

    @Test
    fun `sixel encoder emits raster header palette and rows without control wrappers`() {
        val image = BufferedImage(2, 7, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, if (y < 6) 0xFF0000 else 0x0000FF)
            }
        }

        val sixel = TerminalSixelEncoder.encode(image)

        assertTrue(sixel.startsWith("\"1;1;2;7"))
        assertTrue(";2;100;0;0" in sixel)
        assertTrue(";2;0;0;100" in sixel)
        assertTrue("-" in sixel)
        assertTrue(sixel.all { it.code in 32..126 })
    }

    @Test
    fun `sixel encoder uses image specific palette colors`() {
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 0x115BCB)

        val sixel = TerminalSixelEncoder.encode(image)

        assertTrue(";2;7;36;80" in sixel)
    }
}
