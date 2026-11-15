# Daily Cams 1.1.0
A server-side Minecraft Fabric mod: players place virtual cameras with `/mc_setcamera`, and every day the server renders what each camera sees and posts the picture to a Discord webhook.

## Changes
- New optional client helper, `daily-cams-client-1.1.0+26.3.jar`: with [Freecam](https://modrinth.com/mod/freecam), `/mc_setcamera` places the camera where your freecam view is instead of where your body is. Only needed on clients that want it; the server needs no changes
- Faster renders on Windows hosts: ticks that start up to 30 ms late (Windows' 15.6 ms timer step makes many start after 62.5 ms) no longer make the renderer back off. While Windows was in that state, 1.0.0 took 10.6 minutes for two cameras that otherwise take about 1 minute
- Chunks that fail to load in time are left out of the picture cleanly instead of logging an error per chunk section
- Supports 26.3, requires Java 25, Fabric Loader 0.19.5 or newer, Fabric API, Camera Obscura 2.1.4 and Polymer. The client helper needs Fabric API only
