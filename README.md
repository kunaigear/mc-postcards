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
| `width`, `height` | `1280`, `720` | Picture size. |
| `fov` | `70` | Default field of view. |
| `renderDistance` | `96` | View distance in blocks (16–256). Higher values are slower and load more chunks. |
| `ssaa` | `2` | Supersampling (1 = off). |
| `imageFormat` | `"png"` | `png` or `jpg`. A PNG larger than `maxUploadBytes` is sent as a JPEG instead. |
| `maxUploadBytes` | ~10 MB | Discord's default upload limit for webhooks. |
| `keepLocalCopies` | `true` | Saves each picture under `config/dailycams/renders/<camera>/`. |
| `localCopyRetentionDays` | `30` | Local copies older than this are deleted. `0` keeps them forever. |
| `namedCameraLimit` | `3` | Named cameras allowed for the elevated tier (on top of the personal one). |
| `notifyOwners` | `true` | Tells online owners in chat when their camera's daily picture has been posted. |
| `testCooldownSeconds` | `300` | Cooldown on `/mc_camera test` for non-admins. |
| `chunkLoadTimeoutSeconds` | `60` | If the area isn't loaded by then, the camera renders anyway. |

Cameras are stored in `config/dailycams/cameras.json`. The last scheduled run is stored in `state.json`, so
restarts neither skip a run nor repeat one.

## How it works

1. At each scheduled time, all cameras are queued and rendered one at a time.
2. For each camera, the mod adds a temporary chunk ticket around the camera. The ticket is not saved and
   doesn't tick entities. The area loads and generates like it would for a player, without a player being
   there.
3. On a background thread, the mod builds fresh chunk meshes and renders the frame with Camera Obscura's
   `WorldRenderer`, then encodes the image.
4. The ticket is removed and the image is uploaded. HTTP 429 and 5xx responses are retried, and Discord's
   `retry_after` is respected.

The dedicated server's `pause-when-empty-seconds` would stop the game from ticking on an empty server. The
schedule therefore runs on a wall-clock timer, and while renders are queued the mod keeps the server from
pausing. Afterwards it may pause again.

At 960×540 a render takes roughly 10–15 seconds of background CPU time. The server thread is never blocked.

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

`./gradlew runDevtestServer` starts the same server with a dev-only test mod (`src/devtest`). Running `dctest` in
its console runs the commands as fake players with op levels 0, 2 and 4 and logs every reply. The test mod is
never included in the release jar.
