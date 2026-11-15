# Daily Cams 1.2.0
A server-side Minecraft Fabric mod: players place virtual cameras with `/dailycam set`, and every day the server renders what each camera sees and posts the picture to a Discord webhook.

## Changes
- **Breaking:** all commands are now under `/dailycam`, with `/dcam` as a short alias. The old `/mc_setcamera` and `/mc_camera` commands are gone
  - `/mc_setcamera [name] [pos rot]` is now `/dailycam set [name] [pos rot]`
  - `/mc_camera <subcommand>` is now `/dailycam <subcommand>` (`list`, `status`, `info`, `test`, `remove`, `fov`, `shader`, `webhook`, `runall`, `reload`, `help`)
- **Breaking:** the permission node `dailycams:command.setcamera` is now `dailycams:command.use`. Update LuckPerms or similar if you granted or denied it
- The optional client helper (`daily-cams-client-1.2.0+26.3.jar`) now recognizes `/dailycam set` and `/dcam set`. Update it together with the server
- Supports 26.3, requires Java 25, Fabric Loader 0.19.5 or newer, Fabric API, Camera Obscura 2.1.4 and Polymer. The client helper needs Fabric API only
