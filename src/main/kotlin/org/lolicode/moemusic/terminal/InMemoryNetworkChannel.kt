package org.lolicode.moemusic.terminal

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketRegistry
import org.lolicode.moemusic.core.session.UserSessionRegistry
import org.lolicode.moemusic.core.transport.FramedPayloadCodec
import org.lolicode.moemusic.core.transport.NetworkChannel


interface TerminalClientPacketSink {
    fun receiveFromServer(packetId: PacketId, payload: ByteArray)
}

class InMemoryNetworkChannel(
    private val serverRegistry: PacketRegistry,
    private val clientSink: TerminalClientPacketSink,
    private val localUser: MoeMusicUser,
) : NetworkChannel {

    override fun sendToServer(packetId: PacketId, payload: ByteArray) {
        serverRegistry.dispatch(packetId, payload, localUser)
    }

    override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) {
        if (user.id != localUser.id) return
        val frames = if (UserSessionRegistry.supportsFraming(user.id)) {
            FramedPayloadCodec.encode(payload)
        } else {
            listOf(payload)
        }
        frames.forEach { frame -> clientSink.receiveFromServer(packetId, frame) }
    }

    override fun sendToAllClients(packetId: PacketId, payload: ByteArray) {
        sendToClient(localUser, packetId, payload)
    }
}
