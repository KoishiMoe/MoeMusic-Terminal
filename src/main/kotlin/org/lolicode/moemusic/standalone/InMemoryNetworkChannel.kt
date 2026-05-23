package org.lolicode.moemusic.standalone

import org.lolicode.moemusic.api.MoeMusicUser
import org.lolicode.moemusic.core.protocol.PacketId
import org.lolicode.moemusic.core.protocol.PacketRegistry
import org.lolicode.moemusic.core.transport.NetworkChannel

interface StandaloneClientPacketSink {
    fun receiveFromServer(packetId: PacketId, payload: ByteArray)
}

class InMemoryNetworkChannel(
    private val serverRegistry: PacketRegistry,
    private val clientSink: StandaloneClientPacketSink,
    private val localUser: MoeMusicUser,
) : NetworkChannel {

    override fun sendToServer(packetId: PacketId, payload: ByteArray) {
        serverRegistry.dispatch(packetId, payload, localUser)
    }

    override fun sendToClient(user: MoeMusicUser, packetId: PacketId, payload: ByteArray) {
        if (user.id == localUser.id) {
            clientSink.receiveFromServer(packetId, payload)
        }
    }

    override fun sendToAllClients(packetId: PacketId, payload: ByteArray) {
        clientSink.receiveFromServer(packetId, payload)
    }
}
