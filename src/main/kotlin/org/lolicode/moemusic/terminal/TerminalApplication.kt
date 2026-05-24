package org.lolicode.moemusic.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.lolicode.moemusic.api.LocalizedText
import org.lolicode.moemusic.api.model.TrackAddResult
import org.lolicode.moemusic.api.model.TrackInfo
import org.lolicode.moemusic.core.audio.LavaPlayerNativeBootstrap
import org.lolicode.moemusic.core.config.ModConfigManager
import org.lolicode.moemusic.core.contentfilter.ContentFilterRuntime
import org.lolicode.moemusic.core.media.probe.MediaProbeServiceImpl
import org.lolicode.moemusic.core.network.ServerPacketHandlers
import org.lolicode.moemusic.core.network.ServerPacketSessionBridge
import org.lolicode.moemusic.core.permission.PermissionServiceImpl
import org.lolicode.moemusic.core.plugin.PluginManager
import org.lolicode.moemusic.core.protocol.PacketRegistry
import org.lolicode.moemusic.core.ratelimit.RequestRateLimiter
import org.lolicode.moemusic.core.runtime.ServerPluginServices
import org.lolicode.moemusic.core.runtime.ServerRuntimeAdapter
import org.lolicode.moemusic.core.runtime.ServerRuntimeCoordinator
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.user.UserActionServiceImpl
import org.slf4j.LoggerFactory
import java.nio.file.Path

class TerminalApplication(
    val configDir: Path,
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(TerminalApplication::class.java)

    val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val user: TerminalUser = TerminalUser()

    lateinit var client: TerminalClientRuntime
        private set

    private lateinit var channel: InMemoryNetworkChannel
    private var started = false

    fun start() {
        if (started) return

        LavaPlayerNativeBootstrap.configure(configDir = configDir, gameDir = null)
        ModConfigManager.load(configDir)
        ContentFilterRuntime.applyConfig(ModConfigManager.config)

        val packetRegistry = PacketRegistry()
        client = TerminalClientRuntime(configDir, user, scope)
        channel = InMemoryNetworkChannel(packetRegistry, client, user)
        client.attachChannel(channel)

        ServerPacketHandlers(channel, TerminalSessionBridge(user)).registerAll(packetRegistry)
        ServerRuntimeCoordinator.serverInit(
            channel = channel,
            configDir = configDir,
            adapter = TerminalServerAdapter(client),
            pluginServicesFactory = ::buildPluginServices,
        )

        PluginManager.activateClientRuntime(client.playbackService, client.requestService)
        PluginManager.dispatchClientRuntimeLoad()
        client.start()
        started = true
        logger.info("MoeMusic terminal runtime started at {}", configDir)
    }

    fun reloadConfig() {
        val report = ServerRuntimeCoordinator.reloadServerConfigFromDisk()
        ContentFilterRuntime.applyConfig(ModConfigManager.config)
        client.reloadClientConfig()
        client.setStatus(
            "Reloaded config (${report.reloadedBuiltinSections.size} sections, " +
                "${report.pluginConfigsProcessed.size} plugin configs)",
        )
    }

    override fun close() {
        if (!started) return
        runCatching { client.stop() }
        runCatching { PluginManager.dispatchClientRuntimeUnload() }
        runCatching { ServerRuntimeCoordinator.serverShutdown(finalRuntime = true) }
        scope.cancel()
        started = false
        logger.info("MoeMusic terminal runtime stopped.")
    }

    private fun buildPluginServices(
        playbackController: org.lolicode.moemusic.core.playback.ServerPlaybackController,
        trackSubmissionService: org.lolicode.moemusic.core.playback.TrackSubmissionService,
        requestRateLimiter: RequestRateLimiter,
    ): ServerPluginServices {
        val permissionService = PermissionServiceImpl()
        return ServerPluginServices(
            permissionService = permissionService,
            userActionService = UserActionServiceImpl(
                permissionService = permissionService,
                requestRateLimiter = requestRateLimiter,
                searchService = PluginManager.searchService,
                identifierResolutionService = PluginManager.identifierResolutionService,
                trackSubmissionService = trackSubmissionService,
                playbackController = playbackController,
            ),
            mediaProbeService = MediaProbeServiceImpl(),
        )
    }

    private class TerminalServerAdapter(
        private val client: TerminalClientRuntime,
    ) : ServerRuntimeAdapter {
        override fun onUserQueueTrackSkipped(track: TrackInfo, reason: LocalizedText?) {
            client.setStatus("Skipped '${track.displayTitle()}'")
        }

        override fun onTrackSubmitted(track: TrackInfo, result: TrackAddResult) {
            client.setStatus("Queued '${track.displayTitle()}'")
        }
    }

    private class TerminalSessionBridge(
        private val user: TerminalUser,
    ) : ServerPacketSessionBridge {
        override fun activate(sender: org.lolicode.moemusic.api.MoeMusicUser, locale: String): org.lolicode.moemusic.api.MoeMusicUser {
            user.updateLocale(locale)
            UserSessionRegistry.activate(user, locale)
            return user
        }

        override fun standby(sender: org.lolicode.moemusic.api.MoeMusicUser, locale: String): org.lolicode.moemusic.api.MoeMusicUser {
            user.updateLocale(locale)
            UserSessionRegistry.registerStandby(user, locale)
            return user
        }

        override fun handleRegisteredClientLeave(userId: java.util.UUID) {
            val session = UserSessionRegistry.session(userId) ?: return
            if (session.participation != UserSessionRegistry.Participation.ACTIVE) return
            UserSessionRegistry.standby(userId)
            if (UserSessionRegistry.activeCount() == 0) {
                ServerRuntimeCoordinator.releaseNativeAudienceLeaseIfHeld()
            }
        }
    }
}

private fun TrackInfo.displayTitle(): String =
    title.ifBlank { id.ifBlank { "track" } }
