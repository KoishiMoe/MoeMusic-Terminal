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
    fun `sixel encoder emits raster header palette and rows without control wrappers`() {
        val image = BufferedImage(2, 7, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, if (y < 6) 0xFF0000 else 0x0000FF)
            }
        }

        val sixel = TerminalSixelEncoder.encode(image)

        assertTrue(sixel.startsWith("\"1;1;2;7"))
        assertTrue("#180;2;100;0;0" in sixel)
        assertTrue("#5;2;0;0;100" in sixel)
        assertTrue("-" in sixel)
        assertTrue(sixel.all { it.code in 32..126 })
    }
}
