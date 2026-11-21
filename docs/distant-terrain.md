# Distant terrain

[← Back to the README](../README.md)

Beyond the normal render distance (16 chunks), Postcards keeps drawing the world's real blocks, the way Voxy does
in game. By default it reaches 64 chunks (1024 blocks).

![The same camera without (left) and with (right) distant terrain](images/lod-comparison.jpg)

## How it works

It works the way Voxy does, on the server:

- **Real blocks from the saved world.** The server has already saved every chunk that was ever explored. Daily
  Cams reads the saved chunks in the camera's view directly from disk, without loading them into the world, and
  keeps their blocks around the surface, with the sky and block light saved for each block.
- **Baked block models.** Each block's model, the same one the renderer draws up close, is drawn from each of its
  six sides into a small 16x16 picture. Distant blocks show those pictures: their real textures, overlays like the
  grass on a grass block's side, and their shapes, so leaves and plants are partly see-through. Grass, leaves and
  water get their biome colours. Further away, the pictures are averaged down so textures don't flicker.
- **Traced, not meshed.** Wherever the picture would show sky, rays go through the distant blocks: they skip empty
  air quickly, then walk block by block near the surface, through water down to the ground beneath. Every
  supersample gets its own ray, so edges are as smooth as the near terrain's.
- **Lit like the near terrain.** Distant blocks use their saved light (dark under trees and overhangs, lit around
  torches at night), the same corner shading as vanilla, and the same lighting as the blocks up close: with the
  [realistic shader](shader.md#realistic), sun shadows traced through the distant blocks, sky light, water with
  reflections and haze; with the simple shader, the renderer's own shading with the simple shader's shadows.
- **Distance fog.** Distant terrain fades gently into the haze over its last half, so the end of the world isn't
  a hard line.

![Distant terrain up close: stone, snow and spruce forests beyond the full-detail cliff](images/lod-detail.jpg)

Only explored terrain appears: terrain that was never generated stays sky or haze. Postcards doesn't generate it,
since that would cost the server as much as a player exploring it.

Distant terrain is off in dimensions with a roof (the Nether).

## Settings

| Key | Default | Effect |
|---|---|---|
| `lodDistance` | `64` | How far distant terrain reaches, in chunks (17–128, must be more than `renderDistance`). `0` turns it off. |
| `lodCacheMegabytes` | `256` | Memory for keeping distant terrain between renders (0–65536). `0` turns the [cache](#cache) off. |

Run `/postcard reload` after changing them.

## Cache

Cameras near each other see mostly the same distant chunks, and so does a camera from one day to the next. Daily
Cams keeps the chunks it has read in memory, up to `lodCacheMegabytes`, and only reads a chunk from disk again once
it has changed: whenever the server saves a chunk, it's dropped from the cache. When the cache is full, the chunks
used longest ago make room.

A saved chunk takes about 10 KB, so one camera's distant terrain at the default 64 chunks takes up to about 45 MB.
For the cache to help from one day to the next, it needs room for every area with cameras; a smaller cache still
helps the cameras rendered after a nearby one. Admins see how full it is in `/postcard status`. It's emptied when
the server stops.

## Cost

Reading the saved chunks takes a few seconds per camera: on the 2-core test host, about 10 s for about 4,600 chunks
at the default 64 chunks, or about 1 s when they're in the [cache](#cache). Reads go through the server's own chunk storage, a few at a time, and pause while the
server is busy. Drawing the distant blocks adds a few more seconds to the shader. In that test, TPS stayed at 20,
the longest server tick while reading or shading was 12 ms, and memory use didn't change noticeably. Twice the
distance means roughly four times as many chunks to read.
