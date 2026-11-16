# Distant terrain

[← Back to the README](../README.md)

Beyond the normal render distance (16 chunks), Daily Cams draws distant terrain in a simplified form, like Voxy or
Distant Horizons do in game. By default it reaches 64 chunks (1024 blocks).

![The same camera without (left) and with (right) distant terrain](images/lod-comparison.jpg)

## How it works

- **From the saved world.** The server has already saved every chunk that was ever explored. Daily Cams reads the
  saved chunks in the camera's view directly from disk, without loading them into the world, and keeps a small
  height map: the height of each column, its top block's colour (grass, leaves and water in their biome colours),
  and the ground under water.
- **Drawn behind the near terrain.** The near terrain is still drawn in full detail by Camera Obscura. Wherever
  the picture would show sky, a ray is traced through the height map, so distant terrain appears as blocky
  columns, the way Voxy shows it.
- **Lit like the rest of the picture.** With the [realistic shader](shader.md#realistic), distant terrain gets sun
  shadows (traced over the height map), sky light, water with reflections, and haze, and the picture only fades
  into the sky at the end of the distant terrain. The simple shader, and no shader, draw it with plain lighting.

Only explored terrain appears: terrain that was never generated stays sky or haze. Daily Cams doesn't generate it,
since that would cost the server as much as a player exploring it.

Distant terrain is off in dimensions with a roof (the Nether), where a height map only contains the roof.

## Setting

| Key | Default | Effect |
|---|---|---|
| `lodDistance` | `64` | How far distant terrain reaches, in chunks (17–128, must be more than `renderDistance`). `0` turns it off. |

Run `/dailycam reload` after changing it.

## Cost

Reading the saved chunks takes a few seconds per camera: on the 2-core test host, about 6 s for about 4,300 chunks
at the default 64 chunks. Reads go through the server's own chunk storage, a few at a time, and pause while the
server is busy. The longest server tick while reading was 9 ms, TPS stayed at 19–20, and memory use didn't change
noticeably. Twice the distance means roughly four times as many chunks to read.
