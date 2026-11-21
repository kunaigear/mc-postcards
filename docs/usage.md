# Cameras, commands and permissions

[← Back to the README](../README.md)

## Cameras

Every player gets **one personal camera**, named after them. Players with a higher permission tier can also
create **named cameras**, for example one for the town square and one for the spawn.

A camera remembers a position, a viewing direction and a dimension. `/postcard set` uses your eye position and the
direction you're looking, so the picture shows what you see on screen at that moment. It's rendered fresh every
day, so it changes as the world around it changes.

- Your personal camera follows you if you change your username. Run `/postcard set` again and it's renamed.
- A named camera can't take a player's name. That name stays reserved for that player's personal camera.
  Online players, existing personal cameras and the server's `usercache.json` are checked.
- Camera names can use letters, digits, `_` and `-`, up to 32 characters.
- When the daily picture from your camera has been posted, you get a chat message if you're online.

## Permission tiers

| Tier | Permission node | Default | Cameras |
|---|---|---|---|
| Everyone | `postcards:command.use` | all players | 1 personal camera |
| Elevated | `postcards:camera.named` | op level 2 | personal camera + up to `namedCameraLimit` (3) named cameras |
| Highest | `postcards:camera.unlimited` | op level 4 | personal camera + any number of named cameras |
| Admin | `postcards:admin` | op level 3 | manage everyone's cameras, per-camera webhooks, `runall`, `reload` |

`postcards:command.test` (everyone by default) allows previews of your own cameras. Postcards uses Fabric API's
permission API, so LuckPerms and similar mods can grant these nodes instead of op levels.

## Commands

| Command | Who | What it does |
|---|---|---|
| `/postcard set` | everyone | Places your personal camera at your eyes, looking where you look. Run it again to move it. |
| `/postcard set <your name> <x y z> <yaw pitch> [fov]` | everyone | Places your personal camera at an exact position, optionally with a field of view (30–110). `~` works. The dimension is the one you're in, or use `/execute in`. |
| `/postcard set <name> [<x y z> <yaw pitch> [fov]]` | elevated | Creates or moves a named camera. |
| `/postcard list` | everyone | Lists your cameras, with clickable `[test]` and `[remove]` buttons. Admins see all cameras. |
| `/postcard status` | everyone | Shows the next scheduled run, the queue and how many cameras you have. Admins also see how full the [distant terrain cache](distant-terrain.md#cache) is. |
| `/postcard info [name]` | everyone | Shows a camera's details. |
| `/postcard test [name]` | owner / admin | Renders and sends a camera right away. Non-admins have a cooldown (5 minutes by default). |
| `/postcard remove [name]` | owner / admin | Deletes a camera. |
| `/postcard fov <30-110\|default> [name]` | owner / admin | Sets a camera's field of view. |
| `/postcard shader <simple\|realistic\|none\|default> [name]` | owner / admin | Picks the [shader](shader.md) for one camera. |
| `/postcard webhook <name> <url\|default>` | admin | Sends one camera to a different webhook. |
| `/postcard runall` | admin | Renders and sends every camera now. |
| `/postcard reload` | admin | Reloads the config and `cameras.json`. |
| `/postcard help` | everyone | Shows the commands available to you. |

If you leave out `[name]`, the command uses your personal camera. From the server console, always give a name
(and, for `/postcard set`, a position and rotation).

## Client helper: Freecam and your field of view

The server only knows where your body is and nothing about your game settings. The optional client helper fills
in the rest when you place a camera.

Install `postcards-client-<version>.jar` from the
[releases](https://github.com/kunaigear/mc-postcards/releases) on your client (it needs Fabric API). It
checks which options the server's Postcards version supports, so it also works on older servers (the FOV needs
a 1.2.1 or newer server). Then `/postcard set` and `/postcard set <name>` work as before, with two differences:

- **Your field of view:** the camera uses your FOV setting from *Options*, so the picture shows as much as you
  saw on screen. This also applies when you give a position, unless you add a FOV at the end yourself.
- **Freecam:** with [Freecam](https://modrinth.com/mod/freecam) turned on, the camera is placed at your freecam
  view instead of your body, and chat shows *Using your freecam view*. Fly to the view you want, then run the
  command.

Before the command is sent, the helper turns it into `/postcard set <name> <x y z> <yaw pitch> <fov>`. That's a
normal command, so the server needs nothing extra and the usual permissions and limits apply.

- The freecam part also works while spectating a mob in spectator mode, and with other freecam mods that move the
  camera more than a block or two away from your body. Third-person view is left alone.
- With Freecam, your body stays where you left it, so it can show up in a **[Preview now]** picture. Later
  pictures show whoever happens to be there.
- **Spectator mode** works without the helper, because the server knows where a spectator is.
- **Without the helper**, cameras use the server's default FOV (`/postcard fov` changes it per camera). For a
  freecam view, read the position from the F3 screen and type it in: `/postcard set <your name> <x y z> <yaw pitch>`.
  With Freecam, the view is 0.4 blocks above the position F3 shows, so add 0.4 to `y`.

## Tips

- **Placing a camera:** fly or stand where the view looks best, look at the scene and run `/postcard set`. The
  picture is 16:9 and uses a 70° field of view by default, the same as the game's default. With the
  [client helper](#client-helper-freecam-and-your-field-of-view), it uses your own FOV setting instead.
- **Preview:** **[Preview now]** in the confirmation message renders immediately. If you're still standing at the
  camera, you're left out of the picture automatically.
- **Far views:** a camera sees 16 chunks (256 blocks) by default. Terrain further away disappears into fog.
- **Changing angles:** running `/postcard set` again moves your camera. There's nothing to remove first.
