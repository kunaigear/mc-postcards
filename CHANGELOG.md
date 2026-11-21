# Postcards 1.5.0
A server-side Minecraft Fabric mod: players place virtual cameras with `/postcard set`, and every day the server renders what each camera sees and posts the picture to a Discord webhook.

## Changes
- Daily Cams is now called Postcards, and its source is public at https://github.com/kunaigear/mc-postcards
  - The command is `/postcard`. `/dailycam` and `/dcam` still work
  - On first start, `config/dailycams/` moves to `config/postcards/` with your config, cameras and saved renders
  - Permission nodes start with `postcards:` instead of `dailycams:`, for example `postcards:camera.named`. Rename them in LuckPerms or whichever permissions mod you use
  - Discord posts keep the name in your `discordUsername` setting, which is "Daily Cams" unless you changed it
  - The jars are `postcards-<version>.jar` and `postcards-client-<version>.jar`. Remove the old `daily-cams` jars when you update
- Camera Obscura is no longer needed. Postcards has its own copy of its renderer, cut down to still pictures from fixed cameras, so a new Camera Obscura release can't break it. You can remove Camera Obscura from `mods/` unless players use its camera items. Polymer is still required
- Renders use about 6% less CPU and a quarter less memory, with the same pictures: 8–9% less with the `simple` shader or none, 4% less with `realistic`
- New `renderEntities` setting (on by default) for whether mobs and players appear in pictures. It used to be Camera Obscura's setting, and its value is carried over
- License: the renderer is a modified copy of Camera Obscura, which is licensed under the AGPL-3.0, so the server mod's jar is now under the AGPL-3.0 as a whole. Postcards' own code and the client helper stay MIT. `/postcard help` links to the source code
- Supports 26.3, requires Java 25, Fabric Loader 0.19.5 or newer, Fabric API and Polymer. The client helper needs Fabric API only
