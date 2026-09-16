# PvP Dimensions - Development Guide

For what the mod is and how it plays, see [README.md](README.md).

## Installation

Install server-side alongside its declared dependencies (see `fabric.mod.json`); connecting clients
need only Pandorical, and only for the menus. Version targets live in `gradle.properties`
(Minecraft, loader, Fabric API) and `fabric.mod.json` (Java). Pinata is optional: the build
compiles against the sibling checkout's jar, so build `../pinata` first.

## Building

`./gradlew build` leaves the jar in `build/libs`. Pandorical is a subproject pointed at
`../pandorical`, so a change there is compiled straight into this build with nothing published in
between; Pinata is optional and compiled against `../pinata/build/libs`, so build that first (or
`mc-build pinata`) or the build stops and says so. Nothing but this mod's own jar is bundled.

## How an arena is made

The three arena dimensions are ordinary datapack dimensions (`data/pvp-dimensions-justfatlard/dimension`),
made when the world loads, like Amethyst Door's pocket. An arena is a plot in one of them. Its
`Footprint` is published to `Footprints` before any of its chunks is asked for; `ArenaGenerator`
asks `Footprints` for every chunk, builds vanilla noise terrain (or flat layers, or nothing for a
saved terrain to be pasted into) where there is a footprint, and leaves every other chunk empty.
`ArenaBiomes` answers every position in a footprint with the arena's one biome.

`NoiseBasedChunkGenerator` is final, and the game only builds a proper noise state for a
generator of exactly that class, so `ArenaGenerator` wraps one and keeps its own `RandomState`,
made in `createState` from the level's seed, passing it in place of the blank one it is handed.

## Key Files

| File | Responsibility |
|------|---------------|
| `PvpDimensions.java` | Entry point: codecs, events, commands, the ender chest rule |
| `Access.java` | Admin and user tiers: ops, granted players, permission nodes |
| `preset/Preset.java` | Every setting, as plain fields |
| `preset/Fields.java`, `preset/Field.java` | The settings' names, labels, limits, tab and when each shows; the menus and commands both read these |
| `preset/Summary.java` | A preset written out as sentences, and every place the game plays a setting differently from how it reads |
| `preset/Pictures.java` | The editor's arena map and match clock, drawn from a preset |
| `preset/Kinds.java` | The kinds of match a new preset starts from, in families |
| `preset/Presets.java` | Presets on disk, one JSON file each, items through the game's codec |
| `preset/Kit.java`, `preset/ItemList.java` | Item lists laid out by slot, and plain |
| `world/ArenaGenerator.java`, `world/ArenaBiomes.java` | Ground only inside arenas |
| `world/Shaper.java`, `world/Terrain.java` | Depth, material, bedrock, divisions, applied while a chunk generates |
| `world/Divisions.java` | How walls cut a square up, grid or pie slices of equal ground, for the arena and the editor's map alike |
| `world/SavedTerrains.java` | Saving an arena block for block, a chunk a file, and pasting it back |
| `arena/Arenas.java` | Starting, generating, the waiting room, going live, the tick, ending |
| `arena/Travel.java` | Every way in and out: stash, kit, team, game mode, home |
| `arena/Landing.java` | Setting a player down safely at home, and catching a fall out of the world just after |
| `arena/Visit.java` | The player attachment that holds home and the stashed pack |
| `arena/Combat.java` | PvP rules, kills and their rewards, respawning inside |
| `arena/Portals.java`, `arena/Gateways.java` | Lit frames in the world, exit portals inside; what may cross an arena edge, and pets home when one ends |
| `arena/Borders.java` | Each arena's edge as a per-player world border, and the server's own check of it |
| `arena/Lobby.java`, `arena/Teams.java`, `arena/Spawns.java` | Waiting room, scoreboard teams, where people are put down |
| `arena/Goals.java` | What an arena is won by: kills and lives, mob hunts, capture the flag, colour takeover, base destruction; the win |
| `arena/Mobs.java`, `preset/MobKinds.java` | The arena's own hostile mobs, and the game's kept out; the kinds made from the game's own (babies, riders, angry neutrals, the giant) |
| `arena/TeamBases.java`, `arena/Builds.java` | Each team's building, in four styles: the world's materials in the team's colour, ground levelled under it |
| `arena/Markers.java`, `arena/Hill.java`, `arena/Race.java` | The beacon pad a goal is played on; king of the hill; the race and its start ring |
| `arena/Banks.java`, `arena/TeamChests.java` | Banking's scores; who may open a team's chest, lockpicks and all |
| `arena/Fees.java` | What it costs to join, the pot, and handing it out or back |
| `arena/Horde.java` | The fallen as zombies on the mobs' side |
| `arena/Weather.java`, `arena/Fog.java` | Each arena's own weather, told to its players; fog by how far the server lets a client draw |
| `integration/ChestUtils.java`, `integration/LootEnder.java`, `integration/DeadHeads.java` | Painted team chests and no locking in arenas; picking a team's chest; each arena's head lock time. By reflection |
| `arena/Scoreboard.java`, `ui/GameHud.java` | The fight as a few lines for one player; drawn in the corner with Pandorical's HUD |
| `arena/Loadouts.java`, `ui/PickScreen.java` | Kits to pick from, caps, swapping just after a spawn; the screen for a loadout and a late joiner's team |
| `arena/GoalCompass.java` | A compass pointed at the goal, into Map++'s slot where it can go |
| `arena/Curtain.java` | Division walls coming down from the top, a layer a tick |
| `arena/ModeChanges.java` | The game mode switching partway through |
| `arena/Traps.java` | Who put each block down, for crediting a trap's kill |
| `arena/Moments.java` | The moments worth a cheer: kill bursts, long shots, assists, avenging, captures, the hill, waves, big game, surviving |
| `preset/Sharing.java` | Exporting and importing presets, and the mods a preset needs |
| `arena/GiantSmash.java` | The giant's one attack: a marked spot, cracking, then the smash that breaks it |
| `arena/Waves.java` | Mobs in waves: sending each, counting what is left, what comes after the last |
| `preset/Loot.java` | The templates behind each item list's random fill |
| `arena/Pinatas.java`, `arena/PinataHook.java` | Pinatas, the hook loaded only when Pinata is |
| `arena/AddedSlots.java` | Slots other mods add to the inventory, put aside with the pack |
| `arena/DeathCompasses.java` | Dead Heads' compasses taken back where a preset turns them off |
| `ui/MainScreen.java`, `ui/KindScreen.java`, `ui/EditorScreen.java` | The Pandorical menus: what is running and what to start, where a new preset comes from, and a preset a tab at a time |
| `ui/ChatMenus.java` | The same, as clickable chat |
| `ui/ItemSessions.java` | Editing an item list by holding it |
| `command/PvpCommands.java` | `/pvp` |
| `mixin/NetherPortalBlockMixin.java` | Gates lead in, exits lead home, nothing else in an arena leads anywhere |
| `mixin/PortalForcerMixin.java` | Nether trips never come out in a lit gate |
| `mixin/ServerPlayerMixin.java` | Respawning inside; the arena's own PvP rule, and the horde's |
| `mixin/PlayerListMixin.java` | The arena's border sent after the dimension's |
| `mixin/EntityMixin.java` | No pet or mob carried across a kept-apart arena's edge, or from one arena plot to another |
| `mixin/NaturalSpawnerMixin.java` | No hostile mob spawns by darkness in an arena |
| `mixin/ServerLevelMixin.java`, `mixin/LevelMixin.java` | No world weather in arena worlds; rain there falls by each arena's own |
| `mixin/ItemEntityMixin.java` | The horde picks nothing up |
| `mixin/ServerExplosionMixin.java` | Blasts leave standing what may not be broken |
| `mixin/BlockItemMixin.java`, `mixin/PrimedTntAccessor.java` | Remembering who placed a block; lighting TNT in its placer's name |
| `mixin/MobMixin.java`, `mixin/MobAccessor.java` | The arena's mobs never target each other; a giant given the goals the game leaves it without |

## Testing

`./gradlew runServer` starts a dev server with `-Dpvpdimensions.debug=true`, which adds
`/pvp debug <arena>`: the arena drawn from above and from the side to `run/pvp-debug/`, for
checking terrain without a client. `./gradlew runTestClient` starts a client that joins the dev
server by itself as Tester, `./gradlew runSecondClient` a second as Tester2, for what takes
two: teams, the horde, a race, and `./gradlew runThirdClient` a third as Tester3, for what takes
three: an assist, a teammate avenged. Start them after the server, from an up-to-date build: a
build while the server runs replaces jars it is still reading classes from.

## Art

`generate_icon.py` cuts the icon out of the vanilla jar: two swords crossed in front of a lit
frame. Deterministic; re-run it after a Minecraft version bump.
