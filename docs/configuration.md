# Configuration

[← Back to the README](../README.md)

All settings are in `config/dailycams/config.json`, which is created on the first start. After editing it, run
`/dailycam reload` (or restart the server).

## Discord webhook

1. In Discord, open the settings of the channel the pictures should go to (the gear next to its name).
   You need the *Manage Webhooks* permission.
2. Go to **Integrations → Webhooks → New Webhook**, optionally give it a name and avatar, and click
   **Copy Webhook URL**. It looks like `https://discord.com/api/webhooks/1234567890/AbCdEf...`.
3. Put it in the config:

   ```json
   {
     "webhookUrl": "https://discord.com/api/webhooks/1234567890/AbCdEf...",
     "scheduleTimes": ["12:00"],
     "timeZone": "Europe/Berlin"
   }
   ```

4. Run `/dailycam reload`, then `/dailycam test` to send a picture right away.

Every post is an embed with the picture, the camera name, its coordinates and dimension, and the owner.

- **Ping a role with each post:** set `"discordMessage": "<@&ROLE_ID>"`. To get a role ID, turn on Developer Mode in
  Discord, then right-click the role and choose *Copy Role ID*.
- **A different channel for some cameras:** create a webhook there and run `/dailycam webhook <camera> <url>`.
- **Treat the URL like a password:** anyone who has it can post to the channel.

## Options

| Key | Default | What it does |
|---|---|---|
| `webhookUrl` | `""` | Default webhook. If it's empty, pictures are only saved locally. |
| `webhookFormat` | `"discord"` | `discord` posts an embed with the image attached. `generic` posts multipart form data with a `metadata` JSON part and a `file` part, for your own receiver. |
| `discordUsername`, `discordAvatarUrl` | `"Daily Cams"`, `""` | Name and avatar the posts are shown with. |
| `discordMessage` | `""` | Optional text sent with each post, for example a role mention. |
| `scheduleTimes` | `["12:00"]` | One or more times per day (`HH:mm`) at which all cameras are rendered. |
| `timeZone` | `""` | For example `Europe/Berlin`. If empty, the server's time zone is used. |
| `catchUpMissedRuns` | `true` | If the server was offline at a scheduled time, render as soon as it's back. |
| `randomScheduleOffset` | `false` | Starts each run at a random time up to 20 minutes after the scheduled one. A Minecraft day lasts 20 minutes, so the pictures show every time of day equally often instead of the same one every day. `/dailycam status` shows the actual time of the next run. |
| `width`, `height` | `1920`, `1080` | Picture size. |
| `fov` | `70` | Default field of view in degrees. Cameras can override it with `/dailycam fov`; with the client helper, new placements use the player's FOV. |
| `renderDistance` | `16` | View distance in chunks (2–32), like Minecraft's own setting. See [Performance](performance.md#render-distance). |
| `lodDistance` | `64` | How far [distant terrain](distant-terrain.md) reaches, in chunks. `0` turns it off. |
| `ssaa` | `2` | Supersampling factor for smoother edges (1 = off). |
| `shader` | `"simple"` | `simple`, `realistic` or `none`. See [Shaders](shader.md). |
| `shaderSettings` | | Settings of the simple shader. See [Shaders](shader.md#settings-1). |
| `realisticSettings` | | Settings of the realistic shader (exposure, night brightness, clouds…). See [Shaders](shader.md#settings). |
| `renderCpuShare` | `0.25` | How much of one CPU core a render may use (0.05–1.0). See [Performance](performance.md). |
| `imageFormat` | `"jpg"` | `jpg` or `png`. JPEGs keep full colour resolution (4:4:4), so block edges stay crisp. At 1080p a JPEG is usually 0.6–1 MB, a PNG 3–4 MB. |
| `jpegQuality` | `0.9` | JPEG quality (0.1–1.0). |
| `maxUploadBytes` | ~10 MB | Discord's default upload limit. A larger PNG is sent as a JPEG instead, and a larger JPEG at lower quality. |
| `keepLocalCopies` | `true` | Also saves each picture under `config/dailycams/renders/<camera>/`. |
| `localCopyRetentionDays` | `30` | Local copies older than this are deleted. `0` keeps them forever. |
| `namedCameraLimit` | `3` | Named cameras allowed for the elevated tier, on top of the personal one. |
| `notifyOwners` | `true` | Tells online owners in chat when their camera's daily picture has been posted. |
| `testCooldownSeconds` | `300` | Cooldown on `/dailycam test` for non-admins. |
| `chunkLoadTimeoutSeconds` | `120` | If chunk loading makes no progress for this long, the camera renders with what's loaded. |

The file always contains every option, so new options from an update are added to it automatically. Values
you've already set are kept, which also means an existing file keeps its old defaults.

## Files

| File | Contents |
|---|---|
| `config/dailycams/config.json` | The options above. |
| `config/dailycams/cameras.json` | All cameras. Edit only while the server is stopped, or run `/dailycam reload` afterwards. |
| `config/dailycams/state.json` | When the schedule last ran, so restarts neither skip nor repeat a run, and which cameras of that run aren't finished yet. If the server stops during a run, the rest is rendered after the restart (when `catchUpMissedRuns` is on). |
| `config/dailycams/renders/` | Local copies of the pictures, one folder per camera. |

If `config.json` or `cameras.json` has an error, for example a missing comma after editing it by hand, it isn't
loaded. A copy is saved as `<file>.broken-<date>`, and the error is logged and shown by `/dailycam reload`. The
mod keeps the settings and cameras it had loaded before (or uses the defaults at startup) and never overwrites a
broken `config.json`.

Daily Cams also uses Camera Obscura's own config (`config/cameraobscura/config.json`) for things like whether
mobs and players appear in pictures (`renderEntities`).
