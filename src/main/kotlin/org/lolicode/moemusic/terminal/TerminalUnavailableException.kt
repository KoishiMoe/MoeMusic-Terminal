package org.lolicode.moemusic.terminal

class TerminalUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
