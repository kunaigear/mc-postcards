# Daily Cams 1.3.0
A server-side Minecraft Fabric mod: players place virtual cameras with `/dailycam set`, and every day the server renders what each camera sees and posts the picture to a Discord webhook.

## Changes
- The `realistic` shader looks a lot more like Photon: volumetric clouds with bright tops and grey bases, water that reflects the trees, hills and sky around it, caustics in shallow water, and sun rays through gaps in trees and terrain when looking towards a low sun. Two new settings, `sunRays` and `sharpness`
- Trees with the `realistic` shader: leaves stick out of their blocks, light shines through them when looking into the sun, canopies are bright where the sun reaches them and darker inside, and leaf clusters have a little relief up close. Grass and flowers are lit like the ground they grow on, ambient occlusion darkens canopy interiors and the ground under trees, and distant grass and leaves no longer sparkle. The realistic shader now takes about 22 s more per camera than the simple one on a 2-core host
- New `randomScheduleOffset` setting (off by default): each run starts at a random time up to 20 minutes, one Minecraft day, after the scheduled one, so the pictures show every time of day equally often
- Players in a picture get the skin they joined with. Camera Obscura looked each skin up on Mojang's session server, and when Mojang rate-limited the server (HTTP 429), that player stayed in the default skin until a restart
- Reading distant terrain on a busy server (ticks of 25–50 ms) could wait forever and hold up every camera queued after it. It now reads one chunk per second while the server is busy and gives up after 5 minutes with what it has
- A camera looking down from high up no longer loses the distant ground below it
- Glass and water in front of the sky are no longer erased by either shader
- Distant terrain fades into the clouds instead of leaving a hole in them at the far edge
- Supports 26.3, requires Java 25, Fabric Loader 0.19.5 or newer, Fabric API, Camera Obscura 2.1.4 and Polymer. The client helper needs Fabric API only
