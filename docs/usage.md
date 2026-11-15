# Cameras, commands and permissions

[← Back to the README](../README.md)

## Cameras

Every player gets **one personal camera**, named after them. Players with a higher permission tier can also
create **named cameras**, for example one for the town square and one for the spawn.

A camera remembers a position, a viewing direction and a dimension. `/mc_setcamera` uses your eye position and the
direction you're looking, so the picture shows what you see on screen at that moment. It's rendered fresh every
day, so it changes as the world around it changes.

- Your personal camera follows you if you change your username. Run `/mc_setcamera` again and it's renamed.
- A named camera can't take a player's name. That name stays reserved for that player's personal camera.
  Online players, existing personal cameras and the server's `usercache.json` are checked.
- Camera names can use letters, digits, `_` and `-`, up to 32 characters.
- When the daily picture from your camera has been posted, you get a chat message if you're online.

## Permission tiers

| Tier | Permission node | Default | Cameras |
|---|---|---|---|
| Everyone | `dailycams:command.setcamera` | all players | 1 personal camera |
| Elevated | `dailycams:camera.named` | op level 2 | personal camera + up to `namedCameraLimit` (3) named cameras |
| Highest | `dailycams:camera.unlimited` | op level 4 | personal camera + any number of named cameras |
| Admin | `dailycams:admin` | op level 3 | manage everyone's cameras, per-camera webhooks, `runall`, `reload` |

`dailycams:command.test` (everyone by default) allows previews of your own cameras. Daily Cams uses Fabric API's
permission API, so LuckPerms and similar mods can grant these nodes instead of op levels.

## Commands

| Command | Who | What it does |
|---|---|---|
| `/mc_setcamera` | everyone | Places your personal camera at your eyes, looking where you look. Run it again to move it. |
| `/mc_setcamera <your name> <x y z> <yaw pitch>` | everyone | Places your personal camera at an exact position. `~` works. The dimension is the one you're in, or use `/execute in`. |
| `/mc_setcamera <name> [<x y z> <yaw pitch>]` | elevated | Creates or moves a named camera. |
| `/mc_camera list` | everyone | Lists your cameras, with clickable `[test]` and `[remove]` buttons. Admins see all cameras. |
| `/mc_camera status` | everyone | Shows the next scheduled run, the queue and how many cameras you have. |
| `/mc_camera info [name]` | everyone | Shows a camera's details. |
| `/mc_camera test [name]` | owner / admin | Renders and sends a camera right away. Non-admins have a cooldown (5 minutes by default). |
| `/mc_camera remove [name]` | owner / admin | Deletes a camera. |
| `/mc_camera fov <30-110\|default> [name]` | owner / admin | Sets a camera's field of view. |
| `/mc_camera shader <simple\|none\|default> [name]` | owner / admin | Turns the [shader](shader.md) on or off for one camera. |
| `/mc_camera webhook <name> <url\|default>` | admin | Sends one camera to a different webhook. |
| `/mc_camera runall` | admin | Renders and sends every camera now. |
| `/mc_camera reload` | admin | Reloads the config and `cameras.json`. |
| `/mc_camera help` | everyone | Shows the commands available to you. |

If you leave out `[name]`, the command uses your personal camera. From the server console, always give a name
(and, for `/mc_setcamera`, a position and rotation).

## Placing a camera with Freecam

The server only knows where your body is. If you fly around with [Freecam](https://modrinth.com/mod/freecam) and
run `/mc_setcamera`, the camera ends up at your body, not at the view on your screen.

Install `daily-cams-client-<version>.jar` from the
[releases](https://github.com/kunaigear/mc-postcards/releases) on your client, next to Freecam (it needs
Fabric API). Then:

1. Turn on Freecam and fly to the view you want.
2. Run `/mc_setcamera`, or `/mc_setcamera <name>` for a named camera.

The helper turns that into `/mc_setcamera <name> <x y z> <yaw pitch>` with the position and direction of your
view, and shows *Using your freecam view* in chat. The server needs nothing extra, and the usual permissions and
limits apply. Without Freecam turned on, nothing changes.

- The helper also works while spectating a mob in spectator mode, and with other freecam mods that move the
  camera more than a block or two away from your body. Third-person view is left alone.
- Your body stays where you left it, so it can show up in a **[Preview now]** picture. Later pictures show
  whoever happens to be there.
- **Spectator mode** works without the helper, because the server knows where a spectator is.
- **Without the helper**, read the freecam position from the F3 screen and type it in:
  `/mc_setcamera <your name> <x y z> <yaw pitch>`. With Freecam, the view is 0.4 blocks above the position F3
  shows, so add 0.4 to `y`.

## Tips

- **Placing a camera:** fly or stand where the view looks best, look at the scene and run `/mc_setcamera`. The
  picture uses a 70° field of view, the same as the game's default, in a 16:9 frame.
- **Preview:** **[Preview now]** in the confirmation message renders immediately. If you're still standing at the
  camera, you're left out of the picture automatically.
- **Far views:** a camera sees 16 chunks (256 blocks) by default. Terrain further away disappears into fog.
- **Changing angles:** running `/mc_setcamera` again moves your camera. There's nothing to remove first.
