# MoeMusic Standalone Prototype

This is a lightweight single-user bootstrap for the shared MoeMusic runtime.

It intentionally keeps the first UI small:

- TUI only, powered by Lanterna.
- Config is generated at `moemusic.toml` and edited externally.
- Playback uses Java Sound instead of Minecraft/OpenAL.
- Server and client communicate through an in-memory packet bridge so the normal shared request
  and playback protocol still runs.

Run it from this directory:

```bash
../shared/gradlew -p . run
```

For a real text terminal, prefer the application script instead of `gradle run`; Gradle can launch
the JVM without a controlling `/dev/tty`, which Lanterna needs on Unix-like systems:

```bash
../shared/gradlew -p . installDist
./build/install/moemusic-standalone/bin/moemusic-standalone
```

Useful arguments:

```bash
../shared/gradlew -p . run --args="--config-dir /path/to/config"
```

Terminal mode can be selected explicitly:

```bash
./build/install/moemusic-standalone/bin/moemusic-standalone --terminal text
./build/install/moemusic-standalone/bin/moemusic-standalone --terminal swing
```

Plugins can be placed under `<config-dir>/plugins/`, matching the shared core plugin discovery
layout.
