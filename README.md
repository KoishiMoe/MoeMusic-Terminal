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

Optional TUI features:

```bash
./build/install/moemusic-standalone/bin/moemusic-standalone --mouse off
./build/install/moemusic-standalone/bin/moemusic-standalone --cover off
./build/install/moemusic-standalone/bin/moemusic-standalone --cover kitty
```

Mouse support defaults to `auto` and depends on the terminal emitting mouse events. Cover display
defaults to terminal images on known Kitty-protocol terminals, otherwise Unicode half-block
rendering. Use `--cover kitty` to force Kitty graphics, `--cover terminal` for conservative
terminal-image detection, `--cover unicode` for text rendering, or `--cover off` to disable covers.
Covers still fall back to an inline placeholder when a cover is missing, blocked by client media
policy, or fails to decode.

Runtime logs are written to `<config-dir>/standalone.log` while the TUI is active so background
warnings do not corrupt the terminal screen.

The generated launcher also enables native access for unnamed modules. This avoids the Java 24+
restricted native-access warning emitted by LavaPlayer before application logging can redirect
output away from the terminal UI.

Plugins can be placed under `<config-dir>/plugins/`, matching the shared core plugin discovery
layout.
