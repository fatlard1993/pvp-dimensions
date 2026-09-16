# PvP Dimensions

Arenas on demand. Pick a preset from a menu and a fresh piece of ground is made for the fight,
floating alone in a dimension of its own, with a border round it. Everyone is invited with a
button in chat, the empty obsidian frame beside you lights up as a way in, and when the time
runs out everybody is sent back to where they came from.

## Starting one

`/pvp` opens the menu. It shows the arena you are in, the arenas running (with **Join** and, for
their host, **End**), and a tile for every preset you may start. Pick a tile and choose:

- **Invite all** or **Invite none**. Everyone online gets a **[Join]** button in chat once the
  arena is ready. With none, the lit frame and `/pvp invite` are the only ways in.
- **Light frame**: the empty obsidian frame nearest you, within a dozen blocks, lights up and
  leads into the arena for as long as it runs. It goes dark again when the arena ends; the
  obsidian stays. Anyone who walks through it is in.
- Any settings the preset's admin let users change, such as how long it lasts or how big it is:
  set them for this game; the preset itself stays as it was.

The ground takes a few seconds to make, and you are taken in the moment it is ready.

## Who can

- **Admins** do everything: make and edit presets, start any arena, end anyone's. Ops are
  always admins.
- **Users** start arenas from the presets admins have marked for them, one at a time by
  default, and end their own.
- **Everyone else** plays: an invitation or a lit frame is the way in, and the way out is open
  to whoever is inside.

`/pvp grant <player> admin|user` and `/pvp revoke <player>` set the tiers (admins). A
permissions mod can grant the nodes `pvp-dimensions-justfatlard:admin` and `:user` instead.

## Presets

A preset is everything an arena is made from. The menu lists them a row each, with what each is
played for, how many sides and how long it lasts; admins get an Edit button on every row, and
every change is saved as it is made. Each arena keeps its own copy from the moment it starts, so
editing a preset never changes a game already running. Presets live in
`config/pvp-dimensions/presets/`, one file each.

**New** asks what kind of match it's to be, in families, and no two are the same game with a
setting flipped:

- *Everyone for themselves*: **Deathmatch** (first to ten kills), **Last stand** (one life each,
  a closing border, the last one standing), **King of the hill**, **Race**.
- *Side against side*: **Team battle** (a kit each to pick from, a wall that falls),
  **Capture the flag**, **Base raid**, **Colour war**, **Banking**,
  **Fortify then fight** (five creative minutes to dig in, then survival and the walls come down).
- *Together against the mobs*: **Hunt** (thirty of them), **Big game** (three giants),
  **Hold the line** (waves), **Infection** (waves, and the fallen rise as zombies).
- *The rest*: **Pinata party** (with Pinata installed), **Build** (a creative sandbox), **Blank**.

Each starts with the settings that make that game work together, and several come with a moment
or two already paying: a double kill, a capture, a flawless wave. All of it stays editable.

**Sharing a game with another server**: **Export** (on the Game tab, or `/pvp preset export`)
writes the preset to `config/pvp-dimensions/shared/`, with the mods its game needs written on the
front: every item, block, mob, biome and enchantment it names that isn't the game's own, and
Pinata if it has pinatas. A preset on saved ground takes the ground with it, in a folder beside
the file. Copy both to the other server's `shared` folder, and **Import one**, beside the kinds
under **New** (or `/pvp preset import`), lists what it finds. A file needing a mod the server
doesn't have says which, and isn't imported: a kit or a prize missing its modded half would be a
different game.

The editor asks five questions, a tab each, in the order a host thinks them: **Game** (what it is
called and how it is won), **Players**, **Arena**, **Timeline**, **Gear**. A new preset opens on
Game, which holds everything that makes the match: its goal and what wins it, lives, how long it
lasts and the prizes, and at the bottom the whole match written out in sentences. Where the game
plays a setting differently from how it reads, an amber note says so right under it, and the
bottom of Game lists every such note.

### Game

The tab a new preset opens on: what the arena is won by, how long it lasts, what winning is worth,
and what the preset itself is called.

What the arena is won by before its time runs out. Whoever gets there first wins: a title for
everyone inside, and ten seconds to take it in before everyone goes home. If time runs out first,
whoever leads the goal wins it.

- **Lasting till time's up**: no goal, just the fight.
- **Kill count** (with players fighting): the first player to a number of kills wins, or with teams
  the first team between them.
- **Mob kills**: take down a number of mobs, any hostile ones or one chosen kind, counted for
  **each player**, **each team**, or **everyone together**, where you all win or none of you do.
  The chosen kind makes up half of what spawns, even if its own level is off, and a mob-kill goal
  brings mobs day and night even when **Hostile mobs** is none.
- **Survive the waves** (with mobs in waves): clear every wave, together, and you all win.
- **Capture the flag** (teams): each team's base is an unbreakable chest with the team's banner
  beside it and the team's flag, a named banner, inside. Take another team's flag out of their
  chest, carry it home and put it in yours. A carrier glows. A carrier who dies sends the flag
  home, and a flag dropped in lava, hidden in some other chest or otherwise lost goes home by
  itself; the one left behind counts for nothing after that, and vanishes when anyone picks it up,
  so there is only ever one flag a team can lose.
- **Colour takeover** (teams): everyone is handed their team's terracotta at every spawn. The team
  with the most of the ground's surface in their colour when time is up wins, or the first to a
  share of it, if the preset sets one.
- **Destroy their base** (teams): each team starts beside a cube of its terracotta, one to seven
  blocks across. Your own cube can't be broken by your team. Once every block of a cube is gone,
  that team watches as spectators; the last base standing wins.
- **King of the hill**: a gold pad under a beacon's beam, in the middle or anywhere, that can't
  be broken. Every second one side has it to itself counts for that side; two sides on it at once
  is a fight, and counts for nobody. Whoever holds it glows. The first to a set time wins, or with
  none set, whoever held it longest when time is up. **The hill moves** somewhere new every few
  minutes, if you like.
- **Banking** (teams): each team has a bank, a chest painted its colour that can't be broken,
  nor the block under it. What is in it is the team's score: every item worth what **points
  each** lists (a stack of five diamonds in the list makes each diamond worth five), anything not
  listed nothing. The richest bank when time is up wins, or the first to a set score. Gather,
  bring it home, and take from theirs if you can get at it.
- **Race**: the first to stand on an emerald pad under a beacon's beam wins it, for themselves or
  their team. Everyone starts the same distance from it, on a ring round it, and comes back there
  after a death. The finish stands in the middle or anywhere, and is got to **across the ground**,
  **up a tower** by the ladder inside, **up in the sky**, twenty blocks over the ground with
  nothing under it but what you build, or **dug down to**, buried twelve blocks deep with sparks
  rising over it to show where.
- **Lives**, with any goal: each player's own or **one pool per team** (a free for all counts
  each player's own). A player out of lives watches as a spectator. With players fighting, the
  last player or team standing wins; side by side against the mobs, everyone out ends it. Lives
  bring a death back inside even when **Respawn inside** is off.
- **End with one side left**: once only one team, or one player, is still inside.
- **Lasts** five minutes to a week, or for good. The time left is a bar at the top of the screen.

Colour takeover, destroying bases, banking, and a race up into the sky or down underground need
digging and building, so they play in survival whatever the game mode says.

**Prizes**: give a preset one prize, or several, and each win draws one of them at random and
announces it to everyone inside. It is handed to each winner as they get home, on top of their
own pack, so an arena that keeps things apart does not take it back; a winner who was dead or
offline at the end gets it on the way back too. Where it costs something to join, the winners
can share the pot as well (see Gear).

**The preset itself**, at the bottom of the tab:

- **Name**, **Picture**, and whether **users can start it**.
- **Users may change**: settings a user can set for their own game when they start this preset:
  how long it lasts, how big it is, teams, lives, kills to win, mobs to take down, divisions, how
  many mobs. They appear under the preset when a user picks it, and change that game only.
- **This match**: the preset written out in plain sentences, made from it each time, so it can't
  drift from what the arena does.
- **Share**: **Export**, for another server (above).

### Players


- **Free for all** or **Teams**, two to eight, in red, blue, green, yellow, purple, orange, aqua
  and pink. Teams are the game's own scoreboard teams, so names are coloured on every client and
  **friendly fire** is off unless the preset turns it on.
- **Other teams' chests**, where teams have bases or banks: open to anyone, shut to them, or,
  with [Loot Ender](https://github.com/fatlard1993/loot-ender) installed and its lockpicking on,
  opened with a lockpick, Loot Ender's own picking at the lock of a player's claim. With
  [Chest Utils](https://github.com/fatlard1993/chest-utils), a team's chests are painted its
  colour, and nobody can lock any chest inside an arena: taking from the other team's is half of
  some games.
- **Players fight**: on for PvP; off for PvE, where nobody can hurt anybody else and it is
  everyone against the arena's mobs.
- **Names over heads**: shown, teammates only, or hidden, so nobody can tell who is who from across
  the arena. The player list and death messages still name people.
- **Game mode**: survival; adventure, for no digging; or creative, for building. Creative players
  can't be hurt, so a creative arena is a place to build, not fight (and to build ground for
  **saving terrain**). Admins keep their own game mode, to watch or build.
- **Spawn at** random spots, team corners, or the middle.
- **Waiting room**: a glass box over the arena where players gather before the fight. Its floor
  is striped in the team colours, and standing on a colour is how you pick that team. The host
  starts the fight (**Fight!** in the menu, or `/pvp begin`); if nobody does, it starts by itself
  after the server's waiting time, or closes if fewer than two came.
- **Late arrivals**: join in, watch as a spectator, or be turned away. Joining a fight with teams
  already on, a player is put on the smallest team and gets a screen to change it while they've
  only just arrived, to any team that stays even with the rest (`/pvp team` in chat).
- **Ways home**: an exit portal inside, the `/pvp leave` command, both, or only the end.

PvP is on in an arena's fight and off in its waiting room, whatever the server's own pvp rule
says for the rest of the world.

### Arena


The arena from above heads the tab: its walls, flag chests or bases, spawns and exits, placed
where the arena will put them.

- **Ground**: generated, or **saved**.
- **Size**: two to thirty-two chunks across. Small arenas find a fight fast; past sixteen the
  menu says so.
- **World**: the overworld, the nether, or the end: its biomes, its sky, and the blocks its ground
  is made of. Every block of the arena is the chosen **biome**: a desert arena is sand and cacti
  from edge to edge.
- **Shape**: **natural**, the world's own hills, caverns or islands from the game's noise,
  somewhere new every time; or **flat**, the biome's own ground laid dead level: grass on plains,
  sand on a desert, sulfur and cinnabar in sulfur caves, crimson nylium in a crimson forest, end
  stone in the end. **Biome features** puts the biome's trees, fungi, cacti and chorus on the flat
  too, or leaves it bare. Caves are not cut into it.
- **Ground depth**: how far below its surface the ground goes before the void.
- **Material**: as it grows; all one block; **swap** blocks, starting from the world's own
  palette with every block becoming itself; or **layers**, each a block and a share of the depth.
- **Bedrock**: none, a floor, a floor and walls, or a sealed shell.
- **Villages and ruins**, off by default.
- **Team bases**: a building for each team at the middle of its ground, built when the fight
  starts and sized for the team that will hold it: a **camp**, fenced and open round a campfire; a
  **fort**, walled round a yard, with a walkway behind its battlements, arrow slits and a tower at
  each corner; a **tower**, climbed by the ladder inside to a lookout on the roof; or a
  **bunker**, dug in under a flat roof, with a ladder down at each end. Each is the world's own
  stone and wood with the team's colour worked in, and has the team's chest, a crafting table and
  a furnace, with room at its middle for a flag chest, a bank or a base cube. Players spawning in
  team corners spawn at their base. Against the mobs with no teams, there is one, in the middle,
  for everybody. All of it can be broken but the team's chest.
- **Fog**: from none through haze to pea soup, thirty-two blocks. The game draws fog where a
  client stops drawing the world, and a client draws no further than the server tells it to, so
  this works on every client, with no mod; nobody sees further than their own settings let them
  anyway.
- **Divisions**: walls two blocks thick splitting the arena into two to nine parts, **divided as**
  pie slices that all meet in the middle, each with the same share of ground and two neighbours,
  or as a grid, where the middle cells are boxed in. With teams, each side of a wall wears the
  stained glass of the team it faces, or any block chosen per team; team corners then spawn each
  team in its own parts.

**Saving terrain**: generate an arena, build on it, then stand in it and `/pvp terrain save
<name>`. A preset whose ground is **Saved** lays it down again block for block, chests and all.
Saved terrain is kept with the world, in `pvp-dimensions/terrain/`.

### Timeline


Everything that happens at a time, drawn as a strip across the match at the top of the tab.

- **Border closes to**: the border draws in over the arena's life.
- **Walls fall after**: a divided arena's walls come down part way through the fight, all at once
  from the top like a curtain, in the dust and noise of the wall breaking. **Walls hold till
  then**, if you like: nobody can break or blow through one before it falls.
- **Game mode changes**, one or several: after so many minutes, everyone switches to survival,
  adventure or creative, with a title a minute before if you like, and their pack emptied and
  their kit handed out again, so nothing made in creative is carried into the fight. A creative
  spell to build in, a minute's warning, then survival as the walls come down. Admins keep their
  own game mode, as always, and an arena that is creative at any point lets nothing out.
- **Mobs come** steadily, or in waves.
- Steadily: **hostile mobs** none (peaceful), at night, or day and night; **how many**, a few,
  some, lots, or a swarm, for the players inside; and **each kind**, a spawn egg to click from off
  through rare and normal to common, where a common kind turns up six times as often as a rare
  one. Every hostile kind is there in any world, from zombies and breezes to ghasts, piglin
  brutes, shulkers and the warden; the arena's own world's come first. In a new preset every kind
  starts off, and **All off** and **All normal** set them all at once.
- In waves: each **wave** is a row of spawn eggs; a click adds a kind and steps up how many come
  for each player fighting, from half a mob to five. Waves are sent all at once around the
  players. **Between waves** is the break once one is cleared; **next wave comes** can also give a
  wave only so many minutes. The last few of a wave glow. **After the last** wave it comes again,
  the waves start over, or they stop. **Add a wave** starts it at half again the one before.
- **The kinds**, steadily or in waves, are the game's own and a few made from them: baby zombies,
  husks, drowned and zombified piglins, their eggs drawn small; chicken jockeys, spider jockeys,
  skeleton and zombie horsemen and husks on camels, the rider in the corner of the egg; the
  giant; and wolves, bees, iron golems and polar bears, sent in already angry and kept that way.
  Each is its own entry, so a plain zombie is always a grown one on foot and the mix is the
  preset's.
- **The giant** lumbers after its target and smashes the ground where they stand. The ground
  there cracks for a second first, time to get out; then everyone on the spot is hurt, most at
  the middle, and thrown, and the ground breaks in a ragged crater, with whatever was built on
  it. The smash doesn't break chests or anything else holding something (a pinata, a Dead Heads
  head), blocks a creeper couldn't break either, a wall still standing, a flag chest or a base
  cube.
- **Weather**, in an overworld arena: as outside, clear, rain or thunder, **then** something else
  after so many minutes, if you like: clear skies clouding over into a storm halfway through. Each
  arena has its own, eased in and out over a few seconds. The rain is real where it falls: it
  soaks, puts out fires and lets a trident carry its thrower, and thunder brings lightning down
  near the players.
- **Pinatas**, with [Pinata](https://github.com/fatlard1993/pinata) installed: some at the start,
  more every few minutes (even if some still stand, if you like), or a new one after each is
  broken, one to five **at once**. They stand in the middle, anywhere, or anywhere and moving
  every so often.

The arena spawns its own mobs around its players, never right on top of them; no hostile mob
arrives any other way, by darkness, spawner or egg, though what its own bring with them stays: a
slime's halves, an evoker's vexes. Undead spawned in daylight wear a leather cap so they don't
burn, and don't drop it. The arena's mobs are all on one side: an angry iron golem goes for the
players, not the zombies beside it, and a skeleton's stray arrow starts no fight between them. A
chicken or skeleton horse whose rider is killed stays behind, tame; zombie horses and camel husks
are monsters themselves, and have to be taken down too. An angry bee stings once and dies, as it
would anywhere.

### Gear


What you carry, at four stops.

- **Arriving**: the **entry kit**, laid out: armour worn, sword in hand, or **loadouts** to pick
  from, the entry kit the first and more besides, each with a name, its kit, what it **comes back
  with** after a death (nothing, the same, or a pack of its own), and **at most** so many players
  having it at once. Players pick on the way in, from a screen of each loadout's gear (or buttons
  in chat), and **pick again after each death** if the preset says; a pick made just after
  spawning swaps the kit there and then, and `/pvp loadout` brings the screen back. Where there
  are teams, **caps count** each team apart, a team seeing only its own picks, or the whole match,
  everyone seeing who has what. A **compass to the goal**, for the hill, a race or the other
  team's base, points at it and turns when the hill moves; with Map++ it goes into the compass
  slot and draws Map++'s radar with its needle on the goal. And what it **costs to
  join**, taken from what you bring, so a game handing out gear and prizes costs something on a
  survival server too. Nobody gets in without it, nobody pays twice for one arena, and a fight
  that never starts, or leaving before it does, hands it back. **Winners share the pot**:
  everyone's fee split among the winners as they go home, the odd ones over to winners picked at
  random, or back to whoever is still there if nobody wins; off, it's simply spent. Leave a game
  early and your fee stays in it.
- **Fighting**: **per player kill**, and **bonuses** on top for first blood, a kill up close, from
  range, a knock into the void or lava, revenge, ending a streak, three, five or ten in a row, two
  or three kills within ten seconds, a long shot from forty blocks out, a kill by a trap you laid,
  a teammate avenged, and a flag carrier brought down; **moments** besides the kills, each with a
  reward list of its own: an assist, a flag brought home, the hill taken and every full minute it
  is held, a wave cleared and a wave nobody fell in, a giant, warden, ravager or evoker brought
  down, and five minutes alive without dying; **per mob kill**, wherever mobs come, for each of
  the arena's own mobs you take down, not for its chickens; and each **pinata**'s loot, in the order they come, with how many hits it takes. Loot
  spills exactly as it was listed: potions keep their effects, and enchanted or named items keep
  theirs (with Pinata 1.0.1 or later; an older one spills them plain).
- **Dying**: **respawn inside**, where a death comes back in the arena instead of at a bed, with
  nothing, the entry kit, or a respawn kit of its own; and, with Dead Heads installed, **death
  compasses**, off to hand nobody a compass pointing back at where they fell, and **heads locked
  for**: how long a dead player's head, with everything they had, stays theirs before anyone can
  empty it, the server's own time unless the preset says otherwise, down to nobody at all, for an
  arena where what the dead drop is the loot. Against the mobs,
  **the fallen rise as zombies**: whoever is out, out of lives or dead where nobody comes back,
  comes back on the mobs' side instead of watching. The mobs leave them be; they hunt the players
  still standing, who can fight them back, whatever the rule on players fighting. A zombie wears a
  zombie's head and rags it can't take off, carries the preset's **zombie kit** (a stone sword to
  begin with; armour in it is worn in place of the rags), all of it gone when its zombie dies, can't pick anything up, open
  anything, break or build, and rises again elsewhere when put down; its kills and deaths count
  for nothing. When nobody is left standing, the horde has won. Nothing of the horde's comes
  home.
- **Leaving**: **inventories** *pass freely*, as between any two dimensions; are *kept apart*,
  where what you carry waits outside and comes back when you leave, and what you pick up inside
  stays inside; or are *kept apart, rewards out*, the same with a shortlist of item kinds that may
  come home. Kept apart, ender chests are shut, and experience, potion effects and the slots other
  mods add to the inventory (Map Plus Plus's map and compass) are put aside too. A creative arena
  always keeps inventories apart and lets nothing made inside come home. **Winners take home** the
  prize alone, or the prize and everything they were carrying inside.

Nothing alive crosses a kept-apart arena's edge: a wolf tamed inside stays there when you leave,
and goes with the arena when it ends, and your pets at home wait there while you are in, even with
a mod such as Better Companions that brings pets through portals. Passing freely, pets come and
go with you, and any left sitting inside when the arena ends are sent to their owner. Either way,
no pet follows its owner from one arena into another.

Item lists are made by holding the items. **Edit** puts your own pack aside and lays the list
out in its place; arrange it from the creative menu or chests, then **Done** (or `/pvp done`)
keeps what you are holding and gives your own pack back. `/pvp cancel` changes nothing. The **⚄**
beside a list fills it at random to start from: a loadout in one armour tier with a weapon to
match, a small thing for a kill, bigger for a long streak, a prize worth winning, a shortlist of
valuables to let out, pinata loot that gets better with each pinata.

## Commands

| Command | Who | What |
|---|---|---|
| `/pvp` | anyone | The menu, or the same choices as clickable chat on a client without Pandorical |
| `/pvp start <preset> [private \| invite <names \| everyone>]` | users | Start an arena |
| `/pvp join [arena]`, `/pvp leave` | anyone | In and out |
| `/pvp begin`, `/pvp team <colour>` | host, players | Start the fight; pick a team in the waiting room, or just after arriving late |
| `/pvp loadout [number]` | players | The loadout screen, or pick one |
| `/pvp invite <names \| everyone> [arena]`, `/pvp light <arena>` | host, players | Invite more (to the arena you're in or host, unless named); light another frame |
| `/pvp end [arena]`, `/pvp list` | host, anyone | End one; list them |
| `/pvp preset list \| show \| new \| copy \| delete` | admins | Presets; `new` on its own asks what kind of match, `new kind <KIND>` makes one straight off |
| `/pvp preset export <preset>`, `/pvp preset import <file>` | admins | Share a preset with another server |
| `/pvp preset set <preset> <setting> <value>` | admins | Change one setting (for a spawn-egg grid, the mob to click) |
| `/pvp preset items <preset> <list>`, `/pvp done`, `/pvp cancel` | admins | Edit an item list by holding it |
| `/pvp preset shuffle <preset> <list>` | admins | Fill an item list at random |
| `/pvp terrain save \| list \| delete` | admins | Saved terrain |
| `/pvp grant <player> admin\|user`, `/pvp revoke <player>` | admins | Tiers |

## Server settings

In `config/pvp-dimensions/server.properties`, and on the mod's page of the Pandorical mod menu
for ops: **arenas at once** (4), **arenas each user may run** (1), and how many minutes a
**waiting room** waits (15).

## On screen

Players with Pandorical see the fight in the top-left corner of their screen: each team's colour or
each player's face, the score, and a bar filling toward the win that eases as it moves. Holding
the hill or carrying a flag lights your line; underneath it says who is on the hill, who has
whose flag, how far the finish is, how many mobs are left, how many lives you have. It is there
for every goal, from kills and mob hunts to banks, races and takeovers. Players without Pandorical
have the same in words above the hotbar.

**Moments are called out.** A double kill, a long shot, the hill taken, a flawless wave: whoever
earned it gets it in big letters with a sound, everyone inside gets a line about it, and each
moment pays whatever its reward list holds, which is empty until you fill it. Only the moments a
game can have are in its editor: carriers and captures with flags, the hill ones with the hill,
the wave ones with waves.

**Traps count.** A kill nobody struck, from spikes, a cactus, magma, a berry bush or a campfire
the victim was in or standing on, is credited to whoever put that block down, bonuses and all,
and TNT set off by redstone or fire is lit in its placer's name. Your own traps, and your
teammates', credit nobody.

## How it works

Every arena is a plot in one of three dimensions made for them, an overworld, a nether and an
end, a couple of thousand blocks from the next. Outside every arena those dimensions generate
nothing at all. A finished arena's plot is deleted the next time the server starts. Arenas
survive a restart, and anyone who logs in to an arena that ended while they were away is put
back where they came from, with their own things.

Going home, you are set down where you came from, standing on something: the ground there is
loaded before you arrive, a spot in a wall moves up to the first room above it, one in mid-air
drops to the ground or the water below, and one over the void sends you to spawn instead. For a
few seconds after, anyone who falls out of the world anyway is put back where they landed.

## Requirements

Fabric, Fabric API and [Pandorical](https://github.com/fatlard1993/pandorical) on the server, at
the versions in this mod's `gradle.properties`. No client needs a PvP Dimensions jar.

Where these are installed too, it uses them: [Pinata](https://github.com/fatlard1993/pinata) for
pinatas, [Dead Heads](https://github.com/fatlard1993/dead-heads) for its death compasses and each
arena's own head lock time, [Chest Utils](https://github.com/fatlard1993/chest-utils) to paint
team chests in their colours and keep every chest in an arena unlocked,
[Loot Ender](https://github.com/fatlard1993/loot-ender) for lockpicks on another team's chest, and
[Map++](https://github.com/fatlard1993/map-plus-plus) to draw the goal compass's radar in its
compass slot.

## Pandorical

Everything this mod draws is drawn by Pandorical: the menu of games and arenas, the preset editor
with its tabs and its map and clock, the pick-a-team-and-loadout screen, and the scoreboard in the
corner of the screen while a fight is on. Pandorical is required on the server for those to exist
at all.

A player without Pandorical on their client plays the same game, told rather than drawn: the menu
arrives as clickable chat, a preset is edited a section at a time the same way, teams and loadouts
are picked from chat buttons or `/pvp team` and `/pvp loadout`, and the scoreboard's lines come as
words above the hotbar. Nothing about the arena itself, the walls, the mobs, the flags or the
prizes, needs anything on the client.

## Development

Installing, the map of the source and how to test it are in [DEVELOPMENT.md](DEVELOPMENT.md).

## License

MIT, see [LICENSE](LICENSE).
