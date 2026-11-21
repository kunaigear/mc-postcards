# Performance

[← Back to the README](../README.md)

Rendering a picture is real work: the server loads the area around the camera, builds 3D geometry for it and draws
it in software. Postcards does all of that in the background and gives way to the game whenever the server is
busy. Renders take longer on a small server, but players shouldn't notice them.

## How a render works

1. At each scheduled time, all cameras are queued and rendered one at a time.
2. For each camera, the mod works out which chunks it can actually see (its view cone, up to `renderDistance`),
   plus a one-chunk margin. It loads them with temporary chunk tickets, nearest first and a few at a time. The
   tickets aren't saved and don't tick entities. Missing terrain is generated as it would be for a player.
3. A background thread builds geometry for the visible chunk sections, draws the frame, applies
   the [shader](shader.md) and encodes the image.
4. The tickets are removed and the picture is uploaded. HTTP 429 and 5xx responses are retried, and Discord's
   `retry_after` is respected.

A dedicated server stops ticking after it's been empty for a while (`pause-when-empty-seconds`). The schedule runs
on a wall-clock timer, so it still fires on an empty, paused server, and the mod keeps the server awake until the
queue is done.

## Staying out of the way

- **CPU budget:** the render thread uses at most `renderCpuShare` of one core (default 25%). It works in slices of a
  few milliseconds and sleeps in between, while building geometry, drawing and shading.
- **Backing off:** if a server tick takes longer than 25 ms, or starts more than 30 ms late (for example because of
  garbage collection or world generation), the render and the chunk loader pause for a second.
- **Gradual chunk loading:** new chunk requests are only made while the server keeps up. A saved chunk arrives within
  a tick or two, so while every chunk in flight is that quick, up to 8 are requested per tick and 16 at once. As soon
  as one takes longer, usually because it's being generated, it's 2 per tick and 4 at once, and so it is for the
  rest of a camera's chunks once the server has been busy three times while they load. While the server is busy,
  one chunk per second is still requested, so a permanently busy server finishes eventually.

## Measurements

These come from an emulated small host: 2 CPU cores, 3 GB heap and 200 mobs in force-loaded chunks. Each test
rendered one camera over already-generated terrain and one over new terrain. A hitch is a tick that started more
than 100 ms after the one before it (two ticks' worth), which players can notice.

| | Hitches | Worst second | Longest GC pause |
|---|---|---|---|
| Idle server | 4 per minute | 19 TPS | – |
| Without throttling | 42 per minute | 6 TPS | 122 ms |
| With throttling, 720p PNG | 6–7 per minute | 17–19 TPS | 42–75 ms |
| **With throttling, 1080p JPEG (default)** | **5 per minute** | **19–20 TPS** | **61 ms** |
| Same, plus Lithium, C2ME, ScalableLux and others | 8 per minute | 18–20 TPS | 75 ms |

- Both throttled rows were measured with the shader on. The hitch difference between them is within run-to-run
  noise.
- The remaining hitches mostly come from generating new terrain. That only happens the first time a camera renders
  an area, and it costs the same as a player exploring it. On later days the chunks load from disk.
- Renders finish faster with more cores, a higher `renderCpuShare`, or C2ME (which loads and generates chunks
  faster). How long they take today is in the next two sections.
- Each render logs how long it spent building, drawing and shading, and how long it waited for the server.

### Shaders

These come from the [render benchmark](development.md#render-benchmark) on 2 cores, two runs, rendering the village
and peaks cameras from [the documentation pictures](development.md#documentation-pictures) next to 200 cows. The
time runs from queueing the render until the picture was saved. The renders without a shader read the distant terrain
from disk; the other two find it in the [distant terrain cache](distant-terrain.md#cache):

| Shader | Village | Peaks | Of that, shading and encoding |
|---|---|---|---|
| `none` | 37–38 s | 27–29 s | 2–4.5 s |
| `simple` | 23–24 s | 20–21.5 s | 4–6 s |
| `realistic` | 43–46 s | 33–34 s | 19–22 s |

Loading the chunks in view takes 3–5 s, building their geometry 4–12 s, drawing the frame 6–10.5 s, and reading
the distant terrain 8–11 s from disk or about 1 s from the cache. There was at most one hitch per render, from a
garbage collection pause, and the worst second had 19–20 TPS. The idle server had no hitches.

### Weaker hosts

These come from the [render benchmark](development.md#render-benchmark) on a 6-core desktop. It renders the village
and peaks cameras with each shader, six renders in all, next to 200 cows. The idle server had no hitches in any of
these setups. A core count only tells the JVM how many cores to plan for, with `-XX:ActiveProcessorCount`. A quota
takes CPU time away through a cgroup, the way a small VPS or a container limit does.

| Limits | Collector | Six renders | Hitches | Worst second | Longest tick | Longest GC pause |
|---|---|---|---|---|---|---|
| 4 cores | G1 | 183 s | 1 | 19 TPS | 53 ms | 93 ms |
| 2 cores (two runs) | G1 | 185–190 s | 3 | 19–20 TPS | 62–114 ms | 106–127 ms |
| 2 cores, 2 GB heap | G1 | 191 s | 4 | 18 TPS | 101 ms | 97 ms |
| 1 core (two runs) | Serial | 220–225 s | 5–7 | 13–19 TPS | 154–540 ms | 535–592 ms |
| Quota of 2 cores | G1 | 196 s | 6 | 19 TPS | 199 ms | 194 ms |
| Quota of 1 core (two runs) | Serial | 246–249 s | 11 | 13–17 TPS | 554–582 ms | 542–578 ms |
| Quota of half a core (two runs) | Serial | 380–400 s | 28–39 | 1 TPS | 266–1114 ms | 1063–1108 ms |

- Down to one core, the renders take only a fifth longer than with four: the render thread's `renderCpuShare` of
  one core sets the pace, not the cores. With half a core, that share is half of all the CPU there is.
- The worst hitches come from garbage collection. The server allocates about 30 MB/s with just the cows and about
  155 MB/s while rendering. That is about 500 MB for every second of CPU time the render thread gets, and the
  throttle only counts CPU time. With one CPU the JVM picks the Serial collector, which stops the server for every
  collection. In those runs the old generation filled up during the fourth or fifth render and caused one full
  collection of 0.5–0.6 s, or 1.1 s with half a core, which accounts for the worst second. The young collections
  after it took 100–250 ms.
- A real quota hurts more than fewer cores. The collector's threads, the render thread and the server share one
  budget per 100 ms. When it runs out, every thread waits for the next period, even in the middle of a tick.

## Render distance

`renderDistance` is in chunks, like Minecraft's own setting. The default of 16 (256 blocks) shows the landscape
around a scene instead of a wall of fog:

![6 chunks vs 16 chunks](images/render-distance.jpg)

Most of a render's time goes into building geometry for the chunks in view and drawing it, so the cost grows with
`renderDistance`. Values up to 32 are accepted, but expect roughly 4× the work of 16. Resolution matters much less:
going from 720p to 1080p made a render about 6% slower.
