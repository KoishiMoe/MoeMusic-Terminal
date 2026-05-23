package org.lolicode.moemusic.standalone

import org.lolicode.moemusic.api.MoeMusicUser
import java.util.UUID

class StandaloneUser(
    override val id: UUID = UUID.nameUUIDFromBytes("moemusic-standalone-user".toByteArray()),
    override val displayName: String = "Standalone",
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
