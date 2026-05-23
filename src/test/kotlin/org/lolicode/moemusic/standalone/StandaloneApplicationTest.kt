package org.lolicode.moemusic.standalone

import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
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
}
