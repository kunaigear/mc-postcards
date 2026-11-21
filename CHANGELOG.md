# Postcards 1.6.0
A server-side Minecraft Fabric mod: players place virtual cameras with `/postcard set`, and every day the server renders what each camera sees and posts the picture to a Discord webhook.

## Changes
- Pictures can be up to 7680×4320 (8K), up from 3840×2160. The default stays 1920×1080. Measured on 2 cores, 4K takes 1.5–2× as long as 1080p without a shader, about 2× with `simple` and about 3× with `realistic`, which also needs at least 4 GB of heap. 8K is for big servers. See [Performance](https://github.com/kunaigear/mc-postcards/blob/main/docs/performance.md#resolution)
- License: all of Postcards, including the client helper, is now under the AGPL-3.0. In 1.5.0 only the renderer copied from Camera Obscura was, and the rest was MIT
- `/dailycam` and `/dcam`, the command's names from before the rename, are gone. Use `/postcard`
- Supports 26.3, requires Java 25, Fabric Loader 0.19.5 or newer, Fabric API and Polymer. The client helper needs Fabric API only
