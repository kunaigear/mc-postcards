# Daily Cams 1.4.0
A server-side Minecraft Fabric mod: players place virtual cameras with `/dailycam set`, and every day the server renders what each camera sees and posts the picture to a Discord webhook.

## Changes
- Renders are much faster, with the same pictures. On a 2-core host, the village camera from the documentation takes 23–24 s with the `simple` shader and 43–46 s with the `realistic` one, where 1.3.0 took over two minutes. Most of the old time was spent waiting on the server thread for Polymer's chunk lookups, and drawing blocks that end up hidden behind others
- Distant terrain is kept in memory between renders, so a camera near one rendered before, or the same camera the next day, reads it in about 1 s instead of 8–11 s from disk. New `lodCacheMegabytes` setting (256 by default, `0` turns it off). A chunk is dropped from the cache whenever the server saves it, and admins see how full it is in `/dailycam status`
- Saved chunks load faster: up to 8 per tick and 16 at once, back to 2 and 4 once a chunk is slow to arrive, usually because it's being generated. A busy server still gets one chunk per second
- On a 2-core host, TPS stays at 19–20 in the worst second of a render. Garbage collection pauses cause about one hitch every two renders (three in six renders in the benchmark)
- Supports 26.3, requires Java 25, Fabric Loader 0.19.5 or newer, Fabric API, Camera Obscura 2.1.4 and Polymer. The client helper needs Fabric API only
