# Daily Cams 1.2.1
A server-side Minecraft Fabric mod: players place virtual cameras with `/dailycam set`, and every day the server renders what each camera sees and posts the picture to a Discord webhook.

## Changes
- With the optional client helper (`daily-cams-client-1.2.1+26.3.jar`), `/dailycam set` uses the player's own FOV setting, so the picture shows as much as they saw on screen. A FOV given in the command still wins
- `/dailycam set <name> <x y z> <yaw pitch>` takes an optional field of view at the end (30–110)
- Update the server and the client helper together: the 1.2.1 helper sends the new FOV argument, which older servers don't accept
- Supports 26.3, requires Java 25, Fabric Loader 0.19.5 or newer, Fabric API, Camera Obscura 2.1.4 and Polymer. The client helper needs Fabric API only
