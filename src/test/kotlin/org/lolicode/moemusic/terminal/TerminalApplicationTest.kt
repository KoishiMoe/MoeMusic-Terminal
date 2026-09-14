package org.lolicode.moemusic.terminal

import kotlinx.coroutines.runBlocking
import org.lolicode.moemusic.core.protocol.MoeMusicProtocol
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.SearchResponse
import org.lolicode.moemusic.core.protocol.proto.SelectionEntryKindProto
import org.lolicode.moemusic.core.protocol.proto.SelectionEntryProto
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.transport.FramedPayloadCodec
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TerminalApplicationTest {

    @Test
    fun `startup completes local handshake and exposes builtin http source`() {
        val configDir = createTempDirectory("moemusic-terminal-test-")

        TerminalApplication(configDir).use { app ->
            app.start()

            assertTrue(app.client.serverHandshakeReceived)
            val catalog = assertNotNull(app.client.sourceCatalog)
            assertTrue(catalog.sources.any { it.id == "http" })
            assertEquals(MoeMusicProtocol.VERSION, UserSessionRegistry.protocolVersion(app.user.id))
            assertTrue(UserSessionRegistry.supportsFraming(app.user.id))
        }
    }

    @Test
    fun `direct queue request works through in-memory protocol`() = runBlocking {
        val configDir = createTempDirectory("moemusic-terminal-test-")

        TerminalApplication(configDir).use { app ->
            app.start()

            val queue = app.client.requestService.requestFullQueue()

            assertTrue(queue.tracks.isEmpty())
            assertTrue(queue.failureMessage == null)
        }
    }

    @Test
    fun `search responses append later pages`() = runBlocking {
        val configDir = createTempDirectory("moemusic-terminal-test-")

        TerminalApplication(configDir).use { app ->
            app.start()
            val runtime = app.client

            runtime.receiveFromServer(
                PacketIds.SEARCH_RESPONSE,
                FramedPayloadCodec.encode(
                    SearchResponse(
                        request_id = 1,
                        query = "demo",
                        source_id = "http",
                        offset = 0,
                        total = 3,
                        has_more = true,
                        entries = listOf(searchEntry("a"), searchEntry("b")),
                    ).encode(),
                ).single(),
            )
            runtime.receiveFromServer(
                PacketIds.SEARCH_RESPONSE,
                FramedPayloadCodec.encode(
                    SearchResponse(
                        request_id = 2,
                        query = "demo",
                        source_id = "http",
                        offset = 2,
                        total = 3,
                        has_more = false,
                        entries = listOf(searchEntry("c")),
                    ).encode(),
                ).single(),
            )

            assertEquals(listOf("a", "b", "c"), runtime.searchResults.map { it.selectionId })
            assertEquals(3, runtime.searchLoadedCount)
            assertEquals(3, runtime.searchTotal)
            assertTrue(!runtime.searchHasMore)
        }
    }

    @Test
    fun `startup fails closed with PluginIssueException when plugin issue detected`() {
        val configDir = createTempDirectory("moemusic-terminal-test-")
        val pluginsDir = configDir.resolve("plugins")
        java.nio.file.Files.createDirectories(pluginsDir)
        java.nio.file.Files.writeString(pluginsDir.resolve("corrupt.jar"), "not a zip file")

        try {
            TerminalApplication(configDir).use { app ->
                val error = kotlin.test.assertFailsWith<PluginIssueException> {
                    app.start()
                }
                assertTrue(error.report.hasIssues)
                assertEquals(1, error.report.failedPlugins.size)
                assertTrue(error.message?.contains("Plugin issues detected") == true)
            }
        } finally {
            org.lolicode.moemusic.core.plugin.PluginManager.reset()
        }
    }

    private fun searchEntry(id: String): SelectionEntryProto =
        SelectionEntryProto(
            source_id = "http",
            selection_id = id,
            kind = SelectionEntryKindProto.SELECTION_ENTRY_KIND_TRACK,
            title = "Track $id",
            duration_ms = 1_000,
        )
}
