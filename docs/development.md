# Development

[← Back to the README](../README.md)

## Building

You need JDK 25. Loom requires Gradle itself to run on Java 25.

```
./gradlew build
```

This builds two jars in `build/libs/`:

- `daily-cams-<version>+<minecraft>.jar`: the server mod (`src/main`).
- `daily-cams-client-<version>+<minecraft>.jar`: the optional client helper for Freecam and the player's FOV (`src/client`). It only
  depends on Fabric API and shares no code with the server mod.

## Test servers

| Command | What it starts |
|---|---|
| `./gradlew runServer` | A development server in `run/` with Daily Cams, Camera Obscura and Polymer. |
| `./gradlew runDevtestServer` | The same server plus a dev-only test mod (`src/devtest`). |
| `./gradlew runDevtestServer -PsmallServer` | The same again, emulating a 2-core host with a 3 GB heap and a GC log (`run/gc.log`). |
| `./gradlew devtestJar` | The test mod as a jar, to try it on a production server next to other mods. |

The test mod is never part of the release jar. It adds:

- **`dctest`** (console): runs the commands as fake players with op levels 0, 2 and 4 and logs every reply. It
  checks permission tiers, limits, name rules and username changes.
- **A tick profiler:** `run/dctest-ticks.csv` gets one line per server tick, with its duration, the gap since the
  previous tick, and what the renderer was doing (`idle`, `loading`, `building`, `drawing` or `shading`).

## Project layout

Under `src/main/java/io/github/kunaigear/dailycams/`:

| Path | Contents |
|---|---|
| `command/` | The `/dailycam` command and its `/dcam` alias. |
| `camera/` | The camera model and `cameras.json`. |
| `render/` | The render queue, chunk loading, the bridge to Camera Obscura, the throttle and the shader. |
| `schedule/` | The daily schedule. |
| `webhook/` | The Discord and generic webhook upload. |
| `mixin/` | Hooks into Camera Obscura (mesh pinning, invokers, draw throttle) and the server (keep awake while rendering). |

## Releasing

Releases are built by the Gitea workflow in `.gitea/workflows/release.yml`.

1. Set `mod_version` in `gradle.properties` and describe the release in `CHANGELOG.md`.
2. Commit, then push a tag that matches: `git tag v1.2.3 && git push origin v1.2.3`.

The workflow checks that the tag matches `mod_version`, builds the mod, and publishes a release with both jars and
`SHA256_CHECKSUM.txt`, using `CHANGELOG.md` as the release text.
