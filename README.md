# Daily Cams

A Fabric server mod for Minecraft 26.3. Players place virtual cameras with `/mc_setcamera`. Every day the
server renders what each camera sees and posts the picture to a webhook, such as a Discord channel.

Rendering happens entirely on the server: no bot account joins and no client mods are needed. The 3D
rendering is done by [Camera Obscura](https://modrinth.com/mod/camera-obscura), a server-side software
renderer. This mod adds the cameras, the schedule, chunk loading and the webhook upload.

## Installation

Put these in the server's `mods/` folder:

| Mod | Version |
|---|---|
| Daily Cams | `build/libs/daily-cams-1.0.0+26.3.jar` |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 0.166.2+26.3 or newer |
| [Camera Obscura](https://modrinth.com/mod/camera-obscura) | 2.1.4+26.3 |
| [Polymer](https://modrinth.com/mod/polymer) | 0.18.x+26.3 (needed by Camera Obscura) |

Java 25 is required, as for every 26.x server.

Start the server once. Then set `webhookUrl` in `config/dailycams/config.json` and run `/mc_camera reload`.

## Cameras and permissions

Every player gets **one personal camera**, named after them. Players with a higher permission tier can
also create **named cameras**:

| Tier | Permission node | Default | Cameras |
|---|---|---|---|
| Everyone | `dailycams:command.setcamera` | all players | 1 personal camera |
| Elevated | `dailycams:camera.named` | op level 2 | personal camera + up to `namedCameraLimit` (3) named cameras |
| Highest | `dailycams:camera.unlimited` | op level 4 | personal camera + any number of named cameras |
| Admin | `dailycams:admin` | op level 3 | manage everyone's cameras, per-camera webhooks, `runall`, `reload` |

`dailycams:command.test` (everyone) allows previews of your own cameras. The mod uses Fabric API's permission
API, so LuckPerms and similar mods can grant these nodes instead of op levels.

A player's personal camera follows them if they change their username: running `/mc_setcamera` again renames
it. A named camera can't use a player's name. That name stays reserved for that player's personal camera.
Online players, existing personal cameras and the server's `usercache.json` are checked.

## Commands

| Command | Who | What it does |
|---|---|---|
| `/mc_setcamera` | everyone | Places your personal camera at your eyes, looking where you look. Run it again to move the camera. |
| `/mc_setcamera <your name> <x y z> <yaw pitch>` | everyone | Places your personal camera at an exact position. `~` works. The dimension is the one you're in, or use `/execute in`. |
| `/mc_setcamera <name> [<x y z> <yaw pitch>]` | elevated | Creates or moves a named camera. |
| `/mc_camera list` | everyone | Lists your cameras, with clickable `[test]` and `[remove]` buttons. Admins see all cameras. |
| `/mc_camera status` | everyone | Shows the next scheduled run, the queue and how many cameras you have. |
| `/mc_camera info [name]` | everyone | Shows a camera's details. |
| `/mc_camera test [name]` | owner / admin | Renders and sends a camera right away. Non-admins have a cooldown. |
| `/mc_camera remove [name]` | owner / admin | Deletes a camera. |
| `/mc_camera fov <30-110\|default> [name]` | owner / admin | Sets a camera's field of view. |
| `/mc_camera shader <simple\|none\|default> [name]` | owner / admin | Turns the shader on or off for one camera. |
| `/mc_camera webhook <name> <url\|default>` | admin | Sends one camera to a different webhook. |
| `/mc_camera runall` | admin | Renders and sends every camera now. |
| `/mc_camera reload` | admin | Reloads the config and `cameras.json`. |

If you leave out `[name]`, the command uses your personal camera. Camera names can use letters, digits, `_` and
`-`, up to 32 characters. When the daily picture from a player's camera is posted, the owner gets a chat
message if they're online (`notifyOwners`).

## Configuration (`config/dailycams/config.json`)

| Key | Default | Notes |
|---|---|---|
| `webhookUrl` | `""` | Default webhook. If it's empty, pictures are only saved locally. |
| `webhookFormat` | `"discord"` | `discord` posts an embed with the image attached. `generic` posts multipart form data with a `metadata` JSON part and a `file` part. |
| `discordUsername`, `discordAvatarUrl` | | Override the webhook's name and avatar. |
| `discordMessage` | `""` | Optional text sent with each post, for example `<@&roleId>`. |
| `scheduleTimes` | `["12:00"]` | One or more `HH:mm` times per day. |
| `timeZone` | `""` | For example `Europe/Berlin`. If empty, the server's time zone is used. |
| `catchUpMissedRuns` | `true` | If the server was offline at the scheduled time, render as soon as it starts. |
| `width`, `height` | `1920`, `1080` | Picture size. |
| `fov` | `70` | Default field of view. |
| `renderDistance` | `16` | View distance in chunks (2–32), like Minecraft's own setting. Higher values are slower and load more chunks. |
| `ssaa` | `2` | Supersampling (1 = off). |
| `shader` | `"simple"` | `simple` adds sun shadows, warm light, haze, bloom and a colour grade (see [Shader](#shader)). `none` gives plain Camera Obscura output. |
| `shaderSettings` | | Strength of each shader effect: `shadowStrength`, `sunWarmth`, `haze`, `bloom`, `saturation`, `contrast`, `vignette`. |
| `renderCpuShare` | `0.25` | How much of one CPU core a render may use (0.05–1.0). Lower values make renders slower and lighter on the server. See [Server performance](#server-performance). |
| `imageFormat` | `"jpg"` | `jpg` or `png`. JPEGs keep full colour resolution (4:4:4), so block edges stay crisp. At 1080p a JPEG is usually 0.6–1 MB and a PNG 3–4 MB. A PNG larger than `maxUploadBytes` is sent as a JPEG instead. |
| `jpegQuality` | `0.9` | JPEG quality (0.1–1.0). |
| `maxUploadBytes` | ~10 MB | Discord's default upload limit for webhooks. |
| `keepLocalCopies` | `true` | Saves each picture under `config/dailycams/renders/<camera>/`. |
| `localCopyRetentionDays` | `30` | Local copies older than this are deleted. `0` keeps them forever. |
| `namedCameraLimit` | `3` | Named cameras allowed for the elevated tier (on top of the personal one). |
| `notifyOwners` | `true` | Tells online owners in chat when their camera's daily picture has been posted. |
| `testCooldownSeconds` | `300` | Cooldown on `/mc_camera test` for non-admins. |
| `chunkLoadTimeoutSeconds` | `120` | If the area isn't loaded by then, the camera renders anyway. |

Cameras are stored in `config/dailycams/cameras.json`. The last scheduled run is stored in `state.json`, so
restarts neither skip a run nor repeat one.

## How it works

1. At each scheduled time, all cameras are queued and rendered one at a time.
2. For each camera, the mod works out which chunks the camera can actually see (its view cone, up to
   `renderDistance`), plus a one-chunk margin. It loads them with temporary chunk tickets, nearest first and only
   a few at a time. The tickets aren't saved and don't tick entities. Missing terrain is generated as it would be
   for a player.
3. On a background thread, the mod builds geometry for the visible chunk sections and renders the frame with
   Camera Obscura's `WorldRenderer`, then encodes the image.
4. The tickets are removed and the image is uploaded. HTTP 429 and 5xx responses are retried, and Discord's
   `retry_after` is respected.

The dedicated server's `pause-when-empty-seconds` would stop the game from ticking on an empty server. The
schedule therefore runs on a wall-clock timer, and while renders are queued the mod keeps the server from
pausing. Afterwards it may pause again.

## Shader

With `"shader": "simple"` (the default), a post-processing pass runs after Camera Obscura has drawn the frame:

- **Sun shadows:** each pixel's world position is reconstructed from the depth buffer, and a ray is traced towards
  the sun through the world's blocks. Opaque blocks and leaves cast shadows. Shadows are scaled by the sky
  light at that spot, so caves and interiors aren't darkened twice. They fade out as the sun sets, and there are
  none at night.
- **Light colour:** sunlit surfaces get a warm tint and shadows a cool, slightly blue one.
- **Haze, bloom and grading:** distant terrain fades a little towards the sky colour, bright areas get a soft glow,
  and a light contrast/saturation grade and a vignette finish the picture.

It adds 1–9 seconds per camera, depending on resolution and CPU. The pass yields to the server like the rest of the render: in the 2-core test
below, the shading phase caused no hitches at all. Every strength can be tuned in `shaderSettings`, and
`/mc_camera shader none` turns it off for a single camera.

## Server performance

Rendering is a background job that gives way to the game:

- **CPU budget:** the render thread uses at most `renderCpuShare` of one core (default 25%). It works in slices of
  a few milliseconds and sleeps in between, both while building geometry and while drawing.
- **Backing off:** if a server tick takes longer than 25 ms, or starts late (for example because of GC or world
  generation), the render thread and the chunk loader pause for a second.
- **Gradual chunk loading:** at most 4 chunk requests are outstanding at once, and new ones are only made while
  the server isn't busy. While it is busy, one chunk per second is still requested so that a permanently loaded
  server finishes eventually.

These numbers come from an emulated small host: 2 CPU cores, 3 GB heap and 200 mobs in force-loaded chunks. Each
test rendered one camera over already-generated terrain and one over new terrain, at the default settings.

| | Hitches > 100 ms | Worst second | Longest GC pause | Time per camera |
|---|---|---|---|---|
| Idle server | 4 per minute | 19 TPS | – | – |
| Without throttling | 42 per minute | 6 TPS | 122 ms | 15–20 s |
| **With throttling, 720p PNG** | **6–7 per minute** | **17–19 TPS** | **42–75 ms** | **50–90 s** |
| **With throttling, 1080p JPEG (default)** | **5 per minute** | **19–20 TPS** | **61 ms** | **53–95 s** |

Both throttled rows were measured with the shader on. Going from 720p to 1080p makes a render about 6% slower,
because most of the time goes into building chunk geometry, which doesn't depend on resolution. The difference in
hitches between the two rows is within run-to-run noise.

The remaining hitches mostly come from generating new terrain, which happens only the first time a camera renders
an area and costs the same as a player exploring it. On later days the chunks load from disk, and the render is
close to an idle server. With more cores or a higher `renderCpuShare`, renders finish faster. Each render logs
how long it spent building, drawing and yielding to the server.

## Caveats

- **Camera Obscura internals.** This mod uses parts of Camera Obscura that aren't a public API: its
  `WorldRenderer` and chunk-mesh cache, accessed through mixins and invokers. It was built and tested against
  Camera Obscura **2.1.4+26.3**. A future Camera Obscura update could break it. If that happens, the game
  fails at startup with a mixin error that names `dailycams`; it doesn't fail silently.
- **What the pictures look like** depends on Camera Obscura's renderer: vanilla block models and textures,
  lighting, ambient occlusion, fog, sky and clouds. Mobs and players appear where Camera Obscura supports them.
  Modded blocks render only if Camera Obscura can read their models. Shaders aren't supported.
- **Webhook URLs are secrets.** Commands only show a shortened form of a URL. Anyone with access to
  `config/dailycams/` can read the full URLs.

## Building

```
./gradlew build        # needs JDK 25 to run Gradle
```

The jar is written to `build/libs/`. `./gradlew runServer` starts a development server in `run/`.

`./gradlew runDevtestServer` starts the same server with a dev-only test mod (`src/devtest`). It also writes
`run/dctest-ticks.csv`, a log of every tick's duration and what the renderer was doing at the time. Add
`-PsmallServer` to emulate a 2-core host with a 3 GB heap and a GC log. Running `dctest` in
its console runs the commands as fake players with op levels 0, 2 and 4 and logs every reply. The test mod is
never included in the release jar.
