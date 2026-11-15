# Performance

[← Back to the README](../README.md)

Rendering a picture is real work: the server loads the area around the camera, builds 3D geometry for it and draws
it in software. Daily Cams does all of that in the background and gives way to the game whenever the server is
busy. Renders take longer on a small server, but players shouldn't notice them.

## How a render works

1. At each scheduled time, all cameras are queued and rendered one at a time.
2. For each camera, the mod works out which chunks it can actually see (its view cone, up to `renderDistance`),
   plus a one-chunk margin. It loads them with temporary chunk tickets, nearest first and a few at a time. The
   tickets aren't saved and don't tick entities. Missing terrain is generated as it would be for a player.
3. A background thread builds geometry for the visible chunk sections, draws the frame with Camera Obscura, applies
   the [shader](shader.md) and encodes the image.
4. The tickets are removed and the picture is uploaded. HTTP 429 and 5xx responses are retried, and Discord's
   `retry_after` is respected.

A dedicated server stops ticking after it's been empty for a while (`pause-when-empty-seconds`). The schedule runs
on a wall-clock timer, so it still fires on an empty, paused server, and the mod keeps the server awake until the
queue is done.

## Staying out of the way

- **CPU budget:** the render thread uses at most `renderCpuShare` of one core (default 25%). It works in slices of a
  few milliseconds and sleeps in between, while building geometry, drawing and shading.
- **Backing off:** if a server tick takes longer than 25 ms, or starts late (for example because of garbage
  collection or world generation), the render and the chunk loader pause for a second.
- **Gradual chunk loading:** at most 4 chunk requests are outstanding at once, and new ones are only made while the
  server keeps up. While it's busy, one chunk per second is still requested, so a permanently busy server finishes
  eventually.

## Measurements

These come from an emulated small host: 2 CPU cores, 3 GB heap and 200 mobs in force-loaded chunks. Each test
rendered one camera over already-generated terrain and one over new terrain. A hitch is a tick that started more
than 100 ms late (two missed ticks), which players can notice.

| | Hitches | Worst second | Longest GC pause | Time per camera |
|---|---|---|---|---|
| Idle server | 4 per minute | 19 TPS | – | – |
| Without throttling | 42 per minute | 6 TPS | 122 ms | 15–20 s |
| With throttling, 720p PNG | 6–7 per minute | 17–19 TPS | 42–75 ms | 50–90 s |
| **With throttling, 1080p JPEG (default)** | **5 per minute** | **19–20 TPS** | **61 ms** | **53–95 s** |
| Same, plus Lithium, C2ME, ScalableLux and others | 8 per minute | 18–20 TPS | 75 ms | 36–58 s |

- Both throttled rows were measured with the shader on. The hitch difference between them is within run-to-run
  noise.
- The remaining hitches mostly come from generating new terrain. That only happens the first time a camera renders
  an area, and it costs the same as a player exploring it. On later days the chunks load from disk.
- Renders finish faster with more cores, a higher `renderCpuShare`, or C2ME (which loads and generates chunks
  faster).
- Each render logs how long it spent building, drawing and shading, and how long it waited for the server.

## Render distance

`renderDistance` is in chunks, like Minecraft's own setting. The default of 16 (256 blocks) shows the landscape
around a scene instead of a wall of fog:

![6 chunks vs 16 chunks](images/render-distance.jpg)

Most of a render's time goes into building geometry for the chunks in view, so the cost grows with
`renderDistance`. Values up to 32 are accepted, but expect roughly 4× the work of 16. Resolution matters much less:
going from 720p to 1080p made a render about 6% slower.
