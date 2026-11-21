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
| `./gradlew runBenchmark` | The [render benchmark](#render-benchmark): its own server and world, renders a fixed scene, reports and stops. |
| `./gradlew devtestJar` | The test mod as a jar, to try it on a production server next to other mods. |

The test mod is never part of the release jar. It adds:

- **`dctest`** (console): runs the commands as fake players with op levels 0, 2 and 4 and logs every reply. It
  checks permission tiers, limits, name rules and username changes.
- **A tick profiler:** `run/dctest-ticks.csv` gets one line per server tick, with its duration, the gap since the
  previous tick, and what the renderer was doing (`idle`, `loading`, `building`, `drawing` or `shading`).
- **`dcgen <x> <z> <yaw> <radius>`** (console): generates the chunks in a 140° wedge in front of a point, out to
  `radius` chunks, so [distant terrain](distant-terrain.md) has something to show on a fresh world.

## Render benchmark

```
./gradlew runBenchmark
```

This starts a test server in `run/benchmark/` with its own world (seed `12345`), never `run/world`. It renders
the village and peaks cameras from [the documentation pictures](#documentation-pictures) with each shader, one after
another, and then stops. A pen of cows in force-loaded chunks keeps the server busy the whole time, like players
would. The first run generates the land in front of the cameras, which takes several minutes. Later runs reuse the
world; delete `run/benchmark/world` to start over.

The report is printed at the end and saved in `run/benchmark/reports/`. It has one row per render, after a row for
30 s of the idle server to compare against:

| Column | Meaning |
|---|---|
| time | From queueing the render until the picture was saved. |
| hitches | Ticks that started more than 100 ms after the one before, which players can notice. |
| worst s TPS | Ticks in the slowest full second. |
| longest tick | The slowest tick, in ms. |
| GC pauses | Count, longest and total, in ms. |
| loading, building, drawing, lod, shading | Time in each render stage. Encoding counts as shading. |

Every run uses the same scene and settings, with the build's default config minus the daily schedule, at noon in
clear weather. Compare runs on the same machine and limits. The distant-terrain cache starts empty, so the first
render of each camera reads the distant terrain from disk and the other two shaders read it from memory.

| Property | Default | What it does |
|---|---|---|
| `-PbenchCores=<n>` | 2 | CPU cores the JVM uses (`-XX:ActiveProcessorCount`). |
| `-PbenchHeap=<size>` | `3G` | Maximum heap (`-Xmx`). |
| `-PbenchMobs=<n>` | 200 | Cows in the pen. |
| `-PbenchMaxHitches=<n>` | off | Fail if the renders together have more hitches than this. |
| `-PbenchMinTps=<n>` | off | Fail if any full second during a render has fewer ticks than this. |
| `-PbenchLabel=<text>` | – | Shown in the report and its file name. |

When a budget is broken, the task fails with the reason. `-XX:ActiveProcessorCount` only tells the JVM how many
threads to plan for; the server can still use every core of the machine. To really take CPU time away, run it in a
cgroup with a CPU quota and the matching core count:

```
systemd-run --user --scope -p CPUQuota=100% ./gradlew --no-daemon runBenchmark -PbenchCores=1
```

Without `--no-daemon`, a Gradle daemon that is already running would start the server outside the quota. The report
shows the quota it ran under. With one core, the JVM picks the Serial garbage collector instead of G1, like on a real
one-core host.

The server keeps its GC log in `run/benchmark/gc.log` (replaced on every run) and allows deep JFR stacks, so a
profile can be taken while it runs: `jcmd <pid> JFR.start settings=profile filename=render.jfr`.

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
