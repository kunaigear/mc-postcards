# Daily Cams

**A daily photo of your Minecraft world, posted to Discord.** Players place virtual cameras with
`/mc_setcamera`. Every day the server renders what each camera sees and sends the picture to a webhook.

![A village by the water, rendered by Daily Cams](docs/images/village.jpg)

- **Server-side only:** no bot account joins and players don't need any mods. Vanilla clients work.
- **Real 3D renders**, made on the server by [Camera Obscura](https://modrinth.com/mod/camera-obscura): 1920×1080,
  16 chunks of view distance, plus an optional shader with sun shadows.
- **One camera per player**, named after them. Staff can add named cameras.
- **Gentle on the server:** renders run in the background and give way to the game. TPS stays at 20 even on a
  2-core host ([measurements](docs/performance.md)).
- **Discord-ready:** an embed with the picture, the camera name, coordinates and owner.

| | | |
|---|---|---|
| ![Mountains](docs/images/mountains.jpg) | ![Meadow](docs/images/meadow.jpg) | ![Coast](docs/images/coast.jpg) |

## Quick start

1. Download `daily-cams-<version>.jar` from the [releases](https://github.com/kunaigear/mc-postcards/releases)
   and put it in your server's `mods/` folder together with:

   | Mod | Version |
   |---|---|
   | [Fabric API](https://modrinth.com/mod/fabric-api) | 0.166.2+26.3 or newer |
   | [Camera Obscura](https://modrinth.com/mod/camera-obscura) | 2.1.4+26.3 |
   | [Polymer](https://modrinth.com/mod/polymer) | 0.18.x+26.3 (required by Camera Obscura) |

   You need Minecraft 26.3, Fabric Loader 0.19.5 or newer, and Java 25.
2. Start the server once. It creates `config/dailycams/config.json`.
3. [Create a Discord webhook](docs/configuration.md#discord-webhook), put its URL in `webhookUrl`, and run
   `/mc_camera reload`.
4. In game, look at something nice and run `/mc_setcamera`. Click **[Preview now]** to get the first picture
   straight away. After that, one arrives every day at 12:00 (configurable).

## Documentation

| | |
|---|---|
| [Cameras, commands and permissions](docs/usage.md) | Personal and named cameras, every command, permission tiers and LuckPerms nodes. |
| [Configuration](docs/configuration.md) | Discord setup, schedule, picture size, every `config.json` option. |
| [Shader](docs/shader.md) | What the built-in shader does and how to tune it. |
| [Performance](docs/performance.md) | How rendering stays out of the server's way, with measurements. |
| [Compatibility](docs/compatibility.md) | Tested mods (Lithium, C2ME, ScalableLux, Jade, JEI, Voxy…) and what players need. |
| [Development](docs/development.md) | Building, test servers and making a release. |

## Good to know

- **It depends on Camera Obscura internals.** Daily Cams uses parts of Camera Obscura that aren't a public API.
  This release is built and tested against Camera Obscura **2.1.4+26.3**. If a later Camera Obscura version
  breaks it, the server stops at startup with a mixin error that names `dailycams`; it won't fail silently.
- **What the pictures look like is up to Camera Obscura's renderer:** vanilla models and textures, lighting,
  fog, sky and clouds. Modded blocks appear only if Camera Obscura can read their models.
- **Webhook URLs are secrets.** Commands only show a shortened form. Anyone who can read
  `config/dailycams/` can see the full URLs.

## License

[MIT](LICENSE). Rendering is done by [Camera Obscura](https://github.com/tomalbrc/camera-obscura) by tomalbrc
(LGPL-3.0), which is a separate download.
