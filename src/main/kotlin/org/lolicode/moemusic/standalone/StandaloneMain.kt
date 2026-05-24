package org.lolicode.moemusic.standalone

import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess
import kotlin.io.path.createDirectories

fun main(args: Array<String>) {
    val options = StandaloneOptions.parse(args) ?: return
    options.configDir.createDirectories()

    val terminal = try {
        StandaloneTui.createTerminal(options.terminalMode, options.mouseMode)
    } catch (e: StandaloneTerminalException) {
        System.err.println(e.message)
        exitProcess(2)
    }

    try {
        StandaloneApplication(options.configDir).use { app ->
            app.start()
            StandaloneTui(
                app = app,
                terminal = terminal,
                options = StandaloneTui.Options(
                    mouseMode = options.mouseMode,
                    coverMode = options.coverMode,
                ),
            ).run()
        }
    } catch (e: StandaloneTerminalException) {
        System.err.println(e.message)
        exitProcess(2)
    }
}

data class StandaloneOptions(
    val configDir: Path = defaultConfigDir(),
    val terminalMode: TerminalMode = TerminalMode.AUTO,
    val mouseMode: MouseMode = MouseMode.AUTO,
    val coverMode: CoverMode = CoverMode.AUTO,
) {
    companion object {
        fun parse(args: Array<String>): StandaloneOptions? {
            var configDir = defaultConfigDir()
            var terminalMode = TerminalMode.AUTO
            var mouseMode = MouseMode.AUTO
            var coverMode = CoverMode.AUTO
            var index = 0
            while (index < args.size) {
                when (val arg = args[index]) {
                    "--help", "-h" -> {
                        printHelp()
                        return null
                    }

                    "--config-dir" -> {
                        val value = args.getOrNull(index + 1)
                            ?: error("--config-dir requires a path")
                        configDir = Paths.get(value).toAbsolutePath().normalize()
                        index += 1
                    }

                    "--terminal" -> {
                        val value = args.getOrNull(index + 1)
                            ?: error("--terminal requires one of: auto, text, swing")
                        terminalMode = TerminalMode.parse(value)
                        index += 1
                    }

                    "--mouse" -> {
                        val value = args.getOrNull(index + 1)
                            ?: error("--mouse requires one of: auto, on, off")
                        mouseMode = MouseMode.parse(value)
                        index += 1
                    }

                    "--cover" -> {
                        val value = args.getOrNull(index + 1)
                            ?: error("--cover requires one of: auto, unicode, off")
                        coverMode = CoverMode.parse(value)
                        index += 1
                    }

                    else -> error("Unknown argument: $arg")
                }
                index += 1
            }
            return StandaloneOptions(configDir, terminalMode, mouseMode, coverMode)
        }

        private fun printHelp() {
            println(
                """
                MoeMusic standalone prototype

                Options:
                  --config-dir <path>   Config directory. Defaults to the OS config directory.
                  --terminal <mode>     Terminal mode: auto, text, or swing. Defaults to auto.
                  --mouse <mode>        Mouse mode: auto, on, or off. Defaults to auto.
                  --cover <mode>        Cover display: auto, unicode, or off. Defaults to auto.
                  -h, --help            Show this help.
                """.trimIndent(),
            )
        }
    }
}

enum class MouseMode {
    AUTO,
    ON,
    OFF,
    ;

    companion object {
        fun parse(value: String): MouseMode =
            when (value.trim().lowercase()) {
                "auto" -> AUTO
                "on", "true", "yes" -> ON
                "off", "false", "no" -> OFF
                else -> error("Unknown mouse mode '$value'. Expected one of: auto, on, off")
            }
    }
}

enum class CoverMode {
    AUTO,
    UNICODE,
    OFF,
    ;

    companion object {
        fun parse(value: String): CoverMode =
            when (value.trim().lowercase()) {
                "auto" -> AUTO
                "unicode", "text" -> UNICODE
                "off", "false", "no" -> OFF
                else -> error("Unknown cover mode '$value'. Expected one of: auto, unicode, off")
            }
    }
}

fun defaultConfigDir(
    osName: String = System.getProperty("os.name").orEmpty(),
    env: Map<String, String> = System.getenv(),
    userHome: String = System.getProperty("user.home").orEmpty(),
): Path {
    val normalizedOs = osName.lowercase()
    return when {
        "win" in normalizedOs -> {
            val base = env["APPDATA"].orEmpty().ifBlank { env["LOCALAPPDATA"].orEmpty() }
            Paths.get(base.ifBlank { userHome }).resolve("MoeMusicStandalone")
        }

        "mac" in normalizedOs || "darwin" in normalizedOs ->
            Paths.get(userHome).resolve("Library").resolve("Application Support").resolve("MoeMusicStandalone")

        else -> {
            val base = env["XDG_CONFIG_HOME"].orEmpty().ifBlank {
                Paths.get(userHome).resolve(".config").toString()
            }
            Paths.get(base).resolve("moemusic-standalone")
        }
    }.toAbsolutePath().normalize()
}

enum class TerminalMode {
    AUTO,
    TEXT,
    SWING,
    ;

    companion object {
        fun parse(value: String): TerminalMode =
            when (value.trim().lowercase()) {
                "auto" -> AUTO
                "text", "tty" -> TEXT
                "swing", "gui" -> SWING
                else -> error("Unknown terminal mode '$value'. Expected one of: auto, text, swing")
            }
    }
}
