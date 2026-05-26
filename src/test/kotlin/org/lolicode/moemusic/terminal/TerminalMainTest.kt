package org.lolicode.moemusic.terminal

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalMainTest {

    @Test
    fun `default config fallback prefers environment home over jvm user home`() {
        assertEquals(
            Paths.get("/home/alice/.config/moemusic-terminal"),
            defaultConfigDir(
                osName = "Linux",
                env = mapOf("HOME" to "/home/alice"),
                userHome = "/home/alice/.terminal/instances/overridden",
            ),
        )
    }

    @Test
    fun `default config uses xdg config home when present`() {
        assertEquals(
            Paths.get("/tmp/xdg-config/moemusic-terminal"),
            defaultConfigDir(
                osName = "Linux",
                env = mapOf(
                    "HOME" to "/home/alice",
                    "XDG_CONFIG_HOME" to "/tmp/xdg-config",
                ),
                userHome = "/home/alice/.terminal/instances/overridden",
            ),
        )
    }
}
