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
- **`dcgen <x> <z> <yaw> <radius>`** (console): generates the chunks in a 140° wedge in front of a point, out to
  `radius` chunks, so [distant terrain](distant-terrain.md) has something to show on a fresh world.

## Documentation pictures

The pictures in `docs/images/` are renders from a world with seed `12345` (`level-type=minecraft:normal`), at the
default 1920×1080, FOV 70 and `renderDistance` 16. To render them again, start a test server with that seed and run
this in the console:

```
gamerule advance_time false
gamerule advance_weather false
weather clear
dailycam set village -169.5 81 -859.5 -30 25
dailycam set cherry -415.5 137 768.5 -150 18
dailycam set peaks -303.5 120 -79.5 135 -4
dailycam set coast 9000.5 140 9000.5 30 20
```

Then set the time, choose the shader with `dailycam shader <shader> <camera>`, and run `dailycam test <camera>`.
Renders land in `config/dailycams/renders/<camera>/`.

| Image | Camera | Time | Settings |
|---|---|---|---|
| `village.jpg` | village | `noon` | simple shader |
| `shader-comparison.jpg` | village | `noon` | `none` (left), `simple` (right) |
| `render-distance.jpg` | village | `noon` | `renderDistance` 6 (left), 16 (right) |
| `meadow.jpg` | cherry | `3500` | simple shader |
| `mountains.jpg` | peaks | `8500` | simple shader |
| `coast.jpg` | coast | `1800` | simple shader |
| `shaders-noon.jpg` | village | `noon` | `none`, `simple` and `realistic`, left to right |
| `shaders-sunset.jpg` | village | `12300` | `none`, `simple` and `realistic`, left to right |
| `realistic-comparison.jpg` | village | `noon` | `simple` (left), `realistic` (right) |
| `realistic-cherry.jpg` | cherry | `noon` | realistic shader |
| `realistic-sunset.jpg` | village | `12300` | realistic shader |
| `realistic-night.jpg` | village | `midnight` | realistic shader |
| `realistic-peaks-sunset.jpg` | peaks | `12300` | realistic shader |
| `lod-comparison.jpg` | peaks | `noon` | realistic shader, `lodDistance` 0 (left), 64 (right) |
| `lod-detail.jpg` | peaks | `noon` | the `lodDistance` 64 render, cropped to x 0–1100, y 300–820 |

Distant terrain only shows chunks that exist. Before the distant-terrain pictures, generate the land in front of
the cameras with the test mod: `dcgen -304 -80 135 64` for peaks and `dcgen -170 -860 -30 64` for village.

Pictures are scaled down from the renders: single ones to 1280 px wide (`village.jpg` to 1600 px), and each half of
a side-by-side one to 960 px (800 px with a label for `shader-comparison.jpg` and `render-distance.jpg`), and each
third of `shaders-noon.jpg` and `shaders-sunset.jpg` to 640 px with a label.

## Project layout

Under `src/main/java/io/github/kunaigear/dailycams/`:

| Path | Contents |
|---|---|
| `command/` | The `/dailycam` command and its `/dcam` alias. |
| `camera/` | The camera model and `cameras.json`. |
| `render/` | The render queue, chunk loading, the bridge to Camera Obscura, the throttle and the shader. |
| `schedule/` | The daily schedule. |
| `webhook/` | The Discord and generic webhook upload. |
| `mixin/` | Hooks into Camera Obscura (mesh pinning, invokers, draw throttle, Polymer lookups) and the server (keep awake while rendering, chunk saves for the distant terrain cache). |

## Releasing

Releases are built by the Gitea workflow in `.gitea/workflows/release.yml`.

1. Set `mod_version` in `gradle.properties` and describe the release in `CHANGELOG.md`.
2. Commit, then push a tag that matches: `git tag v1.2.3 && git push origin v1.2.3`.

The workflow checks that the tag matches `mod_version`, builds the mod, and publishes a release with both jars and
`SHA256_CHECKSUM.txt`, using `CHANGELOG.md` as the release text.
