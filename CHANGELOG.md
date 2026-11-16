# Daily Cams 1.2.2
A server-side Minecraft Fabric mod: players place virtual cameras with `/dailycam set`, and every day the server renders what each camera sees and posts the picture to a Discord webhook.

## Changes
- Distant terrain (LOD), built like Voxy: beyond the render distance, the real blocks of the saved world are drawn out to `lodDistance` chunks (default 64, 1024 blocks), read straight from disk without loading the chunks. Blocks show their baked models (textures, overlays, see-through leaves and plants, biome colours), their saved light and vanilla corner shading, and are lit exactly like the near terrain, with a gentle distance fog at the end. About 6 s more per camera; off in the Nether
- New `realistic` shader in the style of Photon: a physically based sky with volumetric clouds that change daily, sunlight coloured by the atmosphere, soft shadows, sun rays, leaves that stick out of their blocks and glow when the sun shines through them, ambient occlusion, water that reflects the trees, hills and sky around it, caustics in shallow water, haze, automatic exposure and filmic (AgX) colour. Nights and caves stay visible. Choose it with `"shader": "realistic"` or `/dailycam shader realistic [name]`; tune it with `realisticSettings`. It takes about 18 s more per camera than the simple shader
- The client helper reads which `/dailycam set` options the server supports and only adds those, so it now works on older servers too (on a 1.2.0 server it no longer causes "Incorrect argument")
- A `config.json` or `cameras.json` with an error is backed up as `<file>.broken-<date>` and no longer overwritten; `/dailycam reload` shows the error and keeps the previous settings and cameras
- Players in a picture get the skin they joined with. Camera Obscura looked each skin up on Mojang's session server, and when Mojang rate-limited the server (HTTP 429), that player stayed in the default skin until a restart
- A daily run interrupted by a stop or crash is finished after the restart
- New `randomScheduleOffset` setting (off by default): each run starts at a random time up to 20 minutes, one Minecraft day, after the scheduled one, so the pictures show every time of day equally often
- Webhook URLs are shown as scheme and host only, and players no longer see webhook error details (they're in the server log, redacted)
- Pictures are encoded before they wait for upload, so a slow webhook can't pile up large images in memory
- Supports 26.3, requires Java 25, Fabric Loader 0.19.5 or newer, Fabric API, Camera Obscura 2.1.4 and Polymer. The client helper needs Fabric API only
