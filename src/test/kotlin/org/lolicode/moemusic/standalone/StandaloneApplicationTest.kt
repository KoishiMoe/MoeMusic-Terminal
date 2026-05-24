package org.lolicode.moemusic.standalone

import kotlinx.coroutines.runBlocking
import org.lolicode.moemusic.core.protocol.PacketIds
import org.lolicode.moemusic.core.protocol.proto.SearchResponse
import org.lolicode.moemusic.core.protocol.proto.SelectionEntryKindProto
import org.lolicode.moemusic.core.protocol.proto.SelectionEntryProto
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StandaloneApplicationTest {

    @Test
    fun `startup completes local handshake and exposes builtin http source`() {
        val configDir = createTempDirectory("moemusic-standalone-test-")

        StandaloneApplication(configDir).use { app ->
            app.start()

            assertTrue(app.client.serverHandshakeReceived)
            val catalog = assertNotNull(app.client.sourceCatalog)
            assertTrue(catalog.sources.any { it.id == "http" })
        }
    }

    @Test
    fun `direct queue request works through in-memory protocol`() = runBlocking {
        val configDir = createTempDirectory("moemusic-standalone-test-")

        StandaloneApplication(configDir).use { app ->
            app.start()

            val queue = app.client.requestService.requestQueue()

            assertTrue(queue.tracks.isEmpty())
            assertTrue(queue.failureMessage == null)
        }
    }

    @Test
    fun `search responses append later pages`() = runBlocking {
        val configDir = createTempDirectory("moemusic-standalone-test-")
        val runtime = StandaloneClientRuntime(configDir, StandaloneUser(), this)

        runtime.receiveFromServer(
            PacketIds.SEARCH_RESPONSE,
            SearchResponse(
                request_id = 1,
                query = "demo",
                source_id = "http",
                offset = 0,
                total = 3,
                has_more = true,
                entries = listOf(searchEntry("a"), searchEntry("b")),
            ).encode(),
        )
        runtime.receiveFromServer(
            PacketIds.SEARCH_RESPONSE,
            SearchResponse(
                request_id = 2,
                query = "demo",
                source_id = "http",
                offset = 2,
                total = 3,
                has_more = false,
                entries = listOf(searchEntry("c")),
            ).encode(),
        )

        assertEquals(listOf("a", "b", "c"), runtime.searchResults.map { it.selectionId })
        assertEquals(3, runtime.searchLoadedCount)
        assertEquals(3, runtime.searchTotal)
        assertTrue(!runtime.searchHasMore)
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
