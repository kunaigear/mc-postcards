# Daily Cams
A server-side Minecraft Fabric mod: players place virtual cameras with `/mc_setcamera`, and every day the server renders what each camera sees and posts the picture to a Discord webhook.

## Changes
- First release
- Every player gets one personal camera named after them; op level 2 adds up to 3 named cameras, op level 4 any number (LuckPerms nodes supported)
- Renders on the server with Camera Obscura: 1920×1080 JPEG, 16 chunks view distance, optional "simple" shader with sun shadows
- Renders run in the background and yield to the server, so TPS stays at 20 even on a 2-core host
- Tested with Lithium, FerriteCore, C2ME, ScalableLux, Krypton, spark, Jade and JEI
- Supports 26.3, requires Java 25, Fabric Loader 0.19.5 or newer, Fabric API, Camera Obscura 2.1.4 and Polymer
