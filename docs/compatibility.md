# Compatibility

[← Back to the README](../README.md)

Daily Cams runs only on the server. Players don't need it, and the only thing it sends them is chat messages.
Vanilla clients can join. The [client helper](usage.md#client-helper-freecam-and-your-field-of-view) (Freecam support and the player's own FOV) is optional.

## Tested mods

These ran together on a production Fabric 26.3 server (Loader 0.20.1), with a player client joining it:

| Mod | Where | Result |
|---|---|---|
| Lithium 0.26.3, FerriteCore 9.0.0, Krypton 0.3.2, spark 1.10.187 | server | No conflicts. |
| C2ME 0.4.2-alpha | server | Works. Renders get faster because chunks load and generate faster. |
| ScalableLux 0.3.0-alpha | server | Works. Lighting and shader shadows are correct with its light engine. |
| Jade 26.3.5, JEI 31.17 | server + client | Works. Players with Jade or JEI stay connected while holding Camera Obscura's camera item, a Polymer item. |
| Voxy 0.2.20-beta + Sodium | client | Works. Voxy is a client mod and isn't needed on the server. |

ModernFix had no 26.3 build at the time of testing.

[Freecam](https://modrinth.com/mod/freecam) 1.5.1 on the client works with the optional Daily Cams client helper,
which places cameras at the freecam view. See the [client helper](usage.md#client-helper-freecam-and-your-field-of-view).

## Voxy

- **Sodium version:** Voxy 0.2.20-beta crashes as soon as you join any world (also in singleplayer) with the newest
  Sodium beta, 0.9.3-beta.5. Players need **Sodium 0.9.3-beta.1**, the version Voxy was built against.
- **Graphics:** Voxy needs OpenGL 4.6. It works on Windows and Linux with current GPU drivers, but not on macOS,
  which only has OpenGL 4.1. Mac players can join, just without LODs.
- **Distant terrain in multiplayer:** on its own, Voxy only builds LODs from terrain each player has loaded
  themselves. For distant terrain sent by the server, install
  [LOD Server Support](https://modrinth.com/mod/lod-server-support) on both the server and the clients. That mod
  hasn't been tested with Daily Cams.

## Other mods

- **Modded blocks** appear in pictures only if Camera Obscura can read their models.
- **Camera Obscura** 2.1.4+26.3 is the tested version. Daily Cams uses parts of it that aren't a public API, so
  later versions may not work. An incompatible version stops the server at startup with a mixin error.
- **Permission mods** such as LuckPerms work through Fabric API's permission API. See the
  [permission nodes](usage.md#permission-tiers).
