package org.lolicode.moemusic.terminal

import org.lolicode.moemusic.api.MoeMusicUser
import java.util.UUID

class TerminalUser(
    override val id: UUID = UUID.nameUUIDFromBytes("moemusic-terminal-user".toByteArray()),
    override val displayName: String = "Terminal",
    locale: String = "en_us",
) : MoeMusicUser() {

    @Volatile
    private var localeValue: String = locale

    override val locale: String
        get() = localeValue

    fun updateLocale(locale: String) {
        localeValue = locale
    }

    override fun hasPermission(permission: String, defaultLevel: Int): Boolean = true
}
