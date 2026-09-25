# Tachyon

Minecraft bots that live on the server. A Tachyon bot is a player nobody plays:
it joins through the same door a player does, so it is in the tab list, it loads
chunks and everyone sees it. It has a player's body and physics (it walks by
pressing a player's keys, jumps, swims, takes fall damage), a player's hands (it
hits with the attack cooldown and breaks blocks with the time each one takes),
and a brain: an AI model behind an API, which it talks to about what it is asked
in the chat.

One jar, in the server's `mods/` folder. Players join without installing
anything, and no bot needs a Minecraft account or a game client of its own.

**Early days (0.3.0).** It walks, follows, hunts, gathers materials, clears
areas and talks; it goes far, round what is in the way and through gates,
bridging gaps and climbing walls with blocks it carries, and digging through
when allowed; it fights with a sword and a bow, wears armor, eats, and tosses
what it does not need, and never takes a piece of a player's build unless a
person orders it to; by itself it fights back, shoots or runs from creepers, backs off when
badly hurt, comes up for air, digs itself out when buried and eats when hungry;
it comes back when it dies (and goes back for what it dropped) and when the
server restarts; its settings have an in-game menu; most of what a player does
is still to come. What is done and what comes next:
[ROADMAP.md](ROADMAP.md).

## Installing

- Minecraft **1.21.1** with **NeoForge 21.1.x**, on a **dedicated server**. On a
  player's own game (single player, LAN) the mod does nothing.
- Put `tachyon-<version>.jar` in the server's `mods/` folder and start it once.
  It writes `tachyon.properties` next to the server jar: that is where the bots'
  brain is set up ([The brain](#the-brain)).

## Commands

| command | who | what |
|---|---|---|
| `/tachyon spawn <name> [count]` | operators | a bot where you stand; with a count over 1, that many (`name1`, `name2`...), each on a tile of its own |
| `/tachyon remove <who>` | owner, operators | the bot leaves |
| `/tachyon goto <who> <x y z>` | owner, operators | walks there |
| `/tachyon follow <who> <player>` | owner, operators | walks after them until stopped |
| `/tachyon stop <who>` | owner, operators | stops whatever it does |
| `/tachyon hunt <who> <mob> [count]` | owner, operators | kills that many of a mob each and picks up the drops; without a count, every one it finds ([Hunting](#hunting-and-killing)) |
| `/tachyon kill <who> <mob> [count]` | owner, operators | kills that many of a mob each with its bow, by sword without one; 1 without a count, 0 for every one it sees |
| `/tachyon gather <who> <block> [count] [item]` | owner, operators | breaks blocks of that kind in the open and picks up what they drop, until it has `count` more (16 without one) of `item` (without one, blocks broken); never a player's build ([Gathering](#gathering)) |
| `/tachyon gather <who> seeds [count]` | owner, operators | cuts grass until it has `count` more wheat seeds (16 without one) |
| `/tachyon clear <who> <from> <to>` | owner, operators | breaks every block in the box, top layer first, with the right tools: whatever is there, builds too, since a person gave the order |
| `/tachyon break <who> [allow\|forbid\|default <block>]` | owner, operators | the blocks it may break on its own to make its way ([Getting there](#getting-there)), or a change to them |
| `/tachyon tell <who> <words>` | owner, operators | says something to a bot, as if in the chat |
| `/tachyon food <who> [ban\|allow\|default <food>]` | owner, operators | the food it does not eat on its own ([Eating](#eating)), or a change to it |
| `/tachyon trash <who> [add\|remove\|default <item>]` | owner, operators | what it tosses as trash ([Tossing](#tossing-and-trash)), or a change to it |
| `/tachyon config [<who>]` | owner, operators | opens the [config menu](#the-config-menu): your bots' settings (and, for operators, the server's defaults) in a chest; with `<who>`, that one bot's |
| `/tachyon settings <who>` | owner, operators | its [settings](#settings): each one's value, and where it comes from |
| `/tachyon set <who> <key> <value>` | owner, operators (some settings: operators only) | changes one of its settings; `default` as the value goes back to the server's default |
| `/tachyon defaults [<key> <value>]` | operators | the server's defaults, for every bot without a value of its own: lists them, or sets one in game; `default` as the value clears it |
| `/tachyon owner <who> [player]` | operators | whose it is, or give it to someone |
| `/tachyon list` | anyone | your bots (every bot, for operators) and what each is doing |
| `/tachyon brain [reload]` | operators | how the bots think, or read `tachyon.properties` again |
| `/tachyon stats [reset]` | operators | what the bots cost the server's tick |

`<who>` is a bot's name, a pattern where `*` stands for any run of characters
(`*` is every bot, `Miner*` every one whose name starts so), or a selector
(`@a[distance=..10]`).

An order given to one bot is told back when it is over (done, or given up), to
whoever gave it, as the bot's `notices` say ([What it tells you
unasked](#talking-to-a-bot)): in its words, whispered; a plain line (`[tachyon]
Ada: arrived at 10, 64, -3 (0.4 from it)`); or not at all. Orders to many bots
are not, or the chat would flood; `list` shows how each goes. A command's own
answer (`[tachyon] Ada: going to ...`) is always said.

**Who can do what.** Operators give orders to every bot. Any other player gives
orders to the bots that are theirs: whoever brought a bot in owns it, and an
operator can hand it to someone else with `owner`. A bot remembers whose it is:
brought in again from the console, it is still its last owner's. Only operators
bring bots in, since every bot costs the server something. **In the chat, a bot
listens only to its owner**, not even to operators: every answer is a call to a
model someone pays for.

## Talking to a bot

Name it in the chat: `Ada, come here`, `Ada hunt three cows`, `what do you see,
Ada?`. It answers in the chat, in the language it was spoken to in, and does what
it can with its tools: come to you, follow, go somewhere, stop, hunt, kill with
its bow, hit what is near, gather a material ("get me some dirt", "chop 20
logs", "I need seeds"), clear a box it was asked to clear, put something in its
hand, put on or take off armor, eat, toss things (to you, too), change its trash
list, tell how it is, look around, tell where and how it last died, tell what it may break on its own. Its brain's
`clear` is for an area someone asked to have cleared, never to get a material,
and it refuses a box that holds blocks players placed: then it says a person can
order it with `/tachyon clear`. When something it was asked is
over (done, or given up), it says so, in its words. Each player can speak to bots
a few times a minute (`per_minute`).

**What it tells you unasked.** A bot also tells its owner what nobody asked
about: how going back for its things after a death went, a player who keeps
hitting it, a full backpack, and what it could not deal with by itself ([What
it does by itself](#what-it-does-by-itself): cornered, drowning, buried for
good, hungry with nothing to eat, out of arrows, a tool about to break,
phantoms); and what the mod must tell: that it died and is back (or left), that
an order a command gave it is over (to whoever gave it), that its brain could not
think. Its `notices` setting says how: `brain` (the default), its brain says
it in its own words (one call to its model, as when it tells how an order went),
whispered to its owner alone (`Ada whispers to you: ...`), since these say where
it died and where its things lie; `plain`, a line to its owner only (`[tachyon]
Ada: got back all 230 items it dropped when it died at 12 64 -30`), no call;
`off`, nothing. A bot with no brain set up says it plainly, and so does one whose
brain is what failed, or that left the game. Each kind of notice is said once
every 10 minutes at most (a death or an order's end, each time), and only while
its owner is in the game.

**Technical lines.** Players read no line of the mod's own but its notices and
its commands' answers. With `verbose` on (off by default), its owner also gets,
as they happen, the lines the server's log has about the bot, with coordinates:
what it does by itself (`[tachyon] Ada backs off: 5 health, a zombie 3 blocks
away`), the tools its brain calls and what they answered, its death as the log
says it (`[tachyon] Ada died (Ada was slain by Zombie) and is back at 12 64
-30`), an order's end. It is for finding out what went wrong.

**A player who keeps hitting it** (3 hits in a row, each within 20 s of the one
before) is told to its owner: `Steve hit it 3 times in 6 s (7 health lost); it
has not hit back`. It does not hit back, unless the operators turned on its
`defend_from_players` ([Fighting back](#fighting-back)). Hits from a player it
hit first, in the 20 s before (with `hunt_players`, its brain may attack a player
named to it; with `defend_from_players`, it fights back), are left out: that
fight is its own. So is a sword's sweep that caught it while the swing was at
something else (two bots side by side against zombies catch each other's).

## Getting there

`goto`, and its brain's `go_to` and `come_here`, are a trip: near, or hundreds of
blocks off, walked a leg at a time as Masurium's bots walk one, round what is in
the way, and told back once it is over.

- **Where.** A tile nobody can stand on is taken as the ground under it, 8 blocks
  down at most, else as a tile beside it: "go to the chest" ends beside the chest,
  not on it; only with neither, as the tile over it (a dirt path's own cell, read
  off where a player stands on it). With building allowed, a tile in the air is
  where it goes: it builds up to it. `come_here` goes to the tile the player stands
  on, on a dirt path or farmland too.
- **Far.** Each leg is the route a search finds, or the stretch of it that gets
  closest; the legs after the first aim at the place's column first, at any
  height, and only then at the tile (a player who said where they stood on a
  hilltop is reached up the hill, not by a tower toward that tile). It is there
  within 1.6 blocks, and 2 up or down. Three legs in a row that end no closer, by
  a block, and it stops, saying so with the numbers: `stuck at 120 64 -30, 23
  blocks from 140 70 -30: the way is blocked by oak_log and I have no permission
  to break it; 4 legs, 6 blocks placed`. Legs that find no way at all it searches
  again for 5 s (a mob moves off, chunks load), then says why.
- **Doors and gates.** Wooden doors and fence gates it opens by hand, and closes
  behind it once it is through; iron doors are walls.
- **Falls.** As long a fall as its health allows: 3 blocks, a block more for
  every 4 health, 12 at most; into water from any height.
- **Water.** It swims, and keeps its head above it; a lake is swum across, never
  bridged. On open water more than 48 blocks from the place it swims straight on
  along the surface, without a search, and from a shore with water ahead it walks
  in and swims on.
- **Stuck.** Six jumps in a row without getting on (a berry bush only slows it,
  and does not count), and the tile it could not get into is left out of its
  searches for 90 s, so the next one finds another way; with no route from
  there, it walks to a tile 2 to 5 blocks aside and searches again from it. Three
  times, and the leg is over.
- **Building** (`build_to_move`, on). With no way on foot, it places a block under
  the next tile to cross a gap (straight on, never over water, which it swims), or
  under its feet at the top of a jump to climb, from a player's reach and as a
  player places one: sneaking, so a chest it builds against is not opened. Only
  cheap blocks it carries: dirt, grass blocks, cobblestone, stone, deepslate,
  andesite, diorite, granite, tuff, calcite, netherrack, blackstone, basalt, sand,
  gravel and planks (and the rest of those families, by the game's tags); carrying
  none, it plans no building. The path finder charges a bridge block 23 ticks and a
  tower block 28, against 4.6 for a step: it builds only where walking, swimming
  and going round do not get it there. It does not pick them up again.
  `build_while_following` (on) lets it build to keep up with whom it follows too.
- **A hole.** Shut in on all four sides with no way out on foot and building off,
  it climbs out on a tower under its own feet, 8 blocks at most, if it carries
  blocks and there is no roof: the one block it places with `build_to_move` off.
- **Digging through** (`break_to_advance`, off). With no way on foot, a route may
  go through blocks on its break list, which it digs (the head's block, then the
  feet's) with the best tool it carries, in the time the block takes: straight
  on, on solid ground, never into lava. The list is what it may break on its own:
  cobblestone, dirt, grass blocks and stone, to start with. Its owner or an
  operator changes it (`/tachyon break <who> allow|forbid|default <block>`); its
  brain reads it (`break_permissions`) and cannot change it. It is looked at again
  on each block as it is dug, and it rules only what the bot breaks on its own to
  make its way: what it is told to break (`clear`) never needed it. Whatever the
  list says, it never digs a block a player placed ([Gathering](#gathering) says
  how the server knows them): a player's build is broken only on a person's order.

## Death, restarts and the night

**When a bot dies**, it comes back 2 seconds later, as a player who pressed
"respawn" does: at its bed or respawn anchor if it set one and it is still
there, else at the world's spawn; whole (health, food, air, no fire, no
effects), with whatever it was doing dropped, and still the same bot (its owner,
settings, data and brain). What it carried stays where it died (it goes back for
it: see below). An order given to
it in those 2 seconds, and what is said to it, it takes up once it is back; a bot
that follows it waits, and follows it again. A bot that dies
over and over (in lava, or where something kills it as soon as it is back) comes
back 5 times in 5 minutes at most: the 6th time, it leaves the game instead. With
`respawn` off it leaves the game when it dies, as it did in 0.1.0. Either way
its owner, if online, hears it as its notices say: `[tachyon] Ada: was slain by
Zombie, and is back at its bed` (at its respawn anchor, its spawn point, the
world's spawn, and why there when its bed was gone or blocked), or `... and left:
5 deaths in 5 minutes` (plainly: it has no brain left to say it). A bot that
left is brought back with `spawn`, whole, as a respawn would bring it.

A spawn point set with `/spawnpoint` where the player stood on a slab, a dirt
path or deep snow is the block the feet were in, that very block, and the game
sends a player whose spawn point it is to the world's spawn instead, calling it
blocked. A bot tries the spot one block up first (by the game's own rule: it and
the block over it neither solid nor a liquid), and keeps its spawn point; blocked
there too, it goes to the world's spawn, as the game decides.

**It goes back for what it dropped**, by itself, 2 seconds after it is back (what
a player drops at a death vanishes after 5 minutes): it walks to where it died
and picks up its own things lying within 12 blocks of it, until it sees none it
can reach; then its owner hears how it went, with the numbers (`got back 212 of
the 230 items it dropped when it died at 12 64 -30; 18 were gone (vanished, or
taken by someone)`). It tries twice at most for one place, within about 6
minutes of the death; never after a death in lava (it all burned), in the void
or by drowning (it lies under the water that drowned it); and only in the
dimension it came back in (crossing to another comes later). Any order given to
it ends the trip, and it says what it left behind. With `recover_items` off it
does not go.

**It remembers its last death**, in its data: where, how (the chat's line, who
killed it), when, what it dropped and what came of it, and how many times it has
died. Its brain knows for 10 minutes after, and asks for it any time with its
`last_death` tool.

**When the server stops**, the bots in the game are recorded with the world
(`<world>/tachyon/roster.json`: name, dimension, position, rotation), and once it
has started again each one comes back where it was, its owner's as before, unless
its `come_back` is off. A server that crashes records them too, as it stops. A bot removed before the stop does not come back; one
whose dimension is gone (a mod's, removed) comes back at the world's spawn. A
broken roster is moved aside as `roster.json.bad-<time>`, and nobody comes back.

**At night**, with `ignore_for_sleep` (the default), the bots are not players to
the night: the players skip it by sleeping without them, and the "n/m players
sleeping" line counts only the players; and no phantom is spawned because of a
bot (phantoms come for a player who has not slept for three days, and once there
they attack any player near, bots too). With it off a bot counts as any player
does, and the night is skipped only if enough bots sleep too; then phantoms may
come for it after three nights without a bed, and the first time one hits it its
owner is told (a night in a bed ends it, but that skips the night for everyone,
so the bot does not decide it; going to bed by itself comes later). Either way
it hits phantoms when they dive. It is the operators' to change, since it changes
the night for everyone.

## Fighting, gear and food

A bot's hands are a player's: it hits with a player's reach, only what it sees,
and waits for the attack to charge; it draws a bow for as long as a player does,
and its arrows fly, hurt and run out as a player's do; it eats for as long as a
player takes to, walking at a fifth of its pace meanwhile, as a player does with
a bow drawn or food at its mouth.

### Weapons and armor

Before every hit it takes the best weapon it carries, from its hotbar or brought
up from its backpack: a real weapon (a sword, an axe, a trident, a mace) before
any tool, then the most damage per second, from the item's own numbers (a mod's
weapons count as they hit). A weapon brought up takes the hotbar slot of the
weakest weapon there, if the new one is better, else an empty one, else that of
a small stack of something of little use.

With `dress_alone` (on by default) it puts on, every 10 seconds, any armor it
carries that protects better than what it wears (armor points, then toughness),
the old piece going where the new one was; a piece with the curse of binding
stays on. Its brain can ask it to put on the best it carries, or one piece (even
a worse one), or take one off into its backpack.

### Hunting and killing

**Hunting** is chasing and killing mobs of one or more kinds (`cow,pig`) with its
best weapon, and picking up what they drop within 12 blocks (not its trash, not
what does not fit, not an item it stood on for 2 s without it going in). It takes
what it sees, out to 128 blocks, the nearest first (and what is within 4, heard):
never a cow behind a hill, which a player in its place would not know of. It
looks every half second, and past 48 blocks every 2 s, each hunter on a tick of
its own; several hunters spread over a herd. When it sees none it goes out
looking: in legs of 48 blocks the way it was told (or faces), turning right when
three legs in a row get it nowhere, for 300 blocks or 3 minutes at most, twice an
errand. Told a count, it stops once that many are dead; with or without one, it
stops when a search finds none (it says how far it looked), and without one, once
its two searches are spent, after half a minute without seeing more. From its
brain it hunts 8 at most, and 8 when not told how many. It never hunts a player,
a tamed mob or a named one, nor a creeper (in melee they blow up), and it stops
below 6 health. Every way a hunt ends says how many it killed.

**Killing** (`kill`) is taking mobs down with its bow, from 10 to 25 blocks away
and in sight; farther or out of sight it walks until it has range and sight, and
what is within reach it finishes with its weapon. It goes after what it sees, as
a hunt does (the nearest within 32, out to 128 every 2 s). It aims where the arrow will
meet the target: led by how the target moves, and raised for the drop, found by
flying the arrow as the game does. It never shoots from water, at a breeze or an
enderman (arrows are no use against them), through a block in the arc, or with a
player on top of the target or in the line of fire. After each arrow it watches
the shot; three that do no harm and it leaves that target alone, as does every
other bot, until the target hurts one of them. It does not pick up what they
drop. A creeper it never goes in on nor hits: it walks to within 16 at most and
shoots from 7 out; closer, it runs, and takes the kill up again from farther
off. Without a bow or arrows, or against a breeze or an enderman, it kills by
sword instead (never a creeper). It stops below 6 health, and when it runs out
of arrows.

**An attack** (`attack`, from its brain) is a few hits, 3 unless told, at what is
within its reach now: the nearest hostile mob (not a calm enderman or zombified
piglin, which are hostile only once angered), or what it is told to hit. It is
not an order: the bot goes on with what it was doing. Out of reach, it says where
the nearest one it sees is.

Whatever hits it makes, a weapon brought to hand (from its backpack, or another
hotbar slot) hits from the next tick on, as a player's: the game starts the new
item's charge again, and a hit in the same tick would land with the old item's
damage.

**Players** are never prey, unless the operators turn on its `hunt_players`: then
its brain's `kill` and `attack` go after a player it is told to by name (never
one in creative or spectator).

### Eating

Its brain can ask it to eat: what it is told to, or, without a name, the best
food it may eat on its own (what fills most of the hunger it lacks, then what
keeps it fed longest), brought up from its backpack if need be. On its own it
never eats food that harms (rotten flesh, spider eyes, raw chicken, pufferfish,
a poisonous potato: any whose effects are harmful), a suspicious stew or a chorus
fruit, nor the food on its banned list: the golden apples, unless its owner or an
operator changes that with `/tachyon food <who> ban|allow|default <food>`. Its
brain reads the list and cannot change it; it eats a banned food only when the
person speaking to it that moment asked for it by name.

### Tossing and trash

Its brain can ask it to toss something, all of it or a count, on the ground or to
someone it sees (it turns to them first); it says how many really went. What it
wears is not tossed this way. **What a bot tosses, that bot never picks up again**
(anyone else does, as usual), for the 5 minutes the item lies there; what it drops
as it dies it does get back. What a bot tosses as its trash, no other bot that
calls it trash too picks up: bots working together would pass it back and forth,
each toss five fresh minutes on the ground.

Its **trash** is what it tosses by itself when its backpack is full (36 slots
taken), and then only: cobblestone, cobbled deepslate, tuff, granite, diorite,
andesite, dirt and gravel, to start with. With `trash_at_once` it tosses its
trash as soon as it picks it up. Either way it keeps one stack of each trash
block it can build with (the one in its hand, else the biggest), and never tosses
what it is using. Its brain may change the list (it concerns only what it
carries), and so may its owner and operators with `/tachyon trash`. Its brain is
told when it tossed its trash to make room, or found its backpack full with
nothing to toss (once in 10 minutes at most: every word to it is a paid call to a
model); with `notices` plain, its owner gets a line instead, with no call, and
with `notices` off, nothing.

## Gathering

"Get me some dirt": it breaks blocks of that kind around it, nearest first, with
the tool that makes them drop what they are (the fastest of those; with none that
speeds a block up, a hand that wears nothing, since a tool spends a use on any
block), picks up what they drop, and stops once it carries as many more as it was
told (16 when not told; 256 at most from its brain): of the item named, or, with
none, of blocks broken. Asked for dirt, it takes grass blocks, podzol, mycelium
and dirt paths too (they drop dirt), and counts the dirt; for cobblestone, stone;
for cobbled deepslate, deepslate. Seeds come from cutting grass
(`gather_seeds`, `/tachyon gather <who> seeds`): about one in eight plants.

It takes what a player in its place would. **Only blocks in the open**: with a
face to the air, that face under the sky or in its sight from where it stands;
never a block seen through rock, and never ores (a search for them all around is
the x-ray; they are mined). It looks 16 blocks each way, 4 down and 6 up, in the
loaded chunks; with none there, it **goes out looking** (legs of 48 blocks the way
it was told or faces, turning right when three get it nowhere, 300 blocks or 3
minutes at most, twice an errand), looking around as it walks.

**Never a piece of a build.** The server keeps where players placed blocks (each
level with the world, 100,000 places at most; a place is forgotten when its block
is broken, blown up, burns or washes away), and it leaves those alone; and, for a
build older than the mod, any block that touches a building block (planks,
stairs, slabs, doors, wool, fences, walls, trapdoors, beds, signs, glass, bricks,
torches, lanterns, chests, crafting tables, furnaces) or a block a player placed;
and logs that are not a tree's (the logs joined to it must touch leaves that grew
there and no building block: a log cabin is logs too). Only a person's
`/tachyon clear` breaks a build. Nor does it take a block with nothing under it,
2 blocks down at most, to catch what it drops: what falls into a gap is lost,
and a bridge a bot built over one is the way back.

It stops when it has enough, when its backpack is full, when it carries no tool
that makes those blocks drop anything, or when a search finds none; every way it
ends says how many it got, and what it left alone and why: `gathered 16 dirt
(broke 16 blocks)`, `broke 0 of 3 stone; I carry no tool that makes stone drop
anything`, `broke 2 of 4 sand; I saw no more sand in the open: I looked 300
blocks to the east; left alone 12 that players placed or built with`. Several
bots gathering together share what is around, each taking the block it goes for.
What it gathers is not its trash meanwhile (dirt is on the list). Its walks to the
blocks go as every walk does ([Getting there](#getting-there)): with
`break_to_advance` on they may dig through blocks on its break list, never one a
player placed; they do not build, since bridges and towers are for trips and for
following.

## What it does by itself

A bot's brain thinks only when it is spoken to, and a call to a model takes
seconds: a fight is measured in ticks. So the body looks after itself, without
its brain, as Masurium's bots did, with their numbers. What it is doing is set
aside while it does, and taken up again after, where it was: an order is never
dropped for it. Its owner hears only of what it could not deal with, with the
numbers ([What it tells you unasked](#talking-to-a-bot)); the rest goes to the
server's log, a line a time (and to its owner too, with its `verbose` on).

### Fighting back

**Hit, it hits back**, for 8 s after the hit: the nearest hostile mob within its
reach, or whatever hurt it, with its best weapon (brought up from its backpack,
which costs the tick a new item's charge starts again in), each hit once the attack
is 90% charged. It never hits first, never a creeper, never its owner's pets, and
never a neutral mob that is not after it (a calm enderman or zombified piglin next
to the zombie that bit it: one hit angers a zombified piglin's whole group). What
hurts it and is no hostile mob (an iron golem, a wolf) it does not fight, as
Masurium's bots did not; badly hurt, it backs off. It keeps walking wherever it was
going while it hits. It waits for a bite to be over.

**What hurts it from afar** (a skeleton, a pillager, a witch), and a mob with a bow
or crossbow it sees taking aim at it (the bow drawn, the head turned to it, as a
player sees it), before the first arrow: with a bow from 10 blocks or more, it
shoots back (never at a witch, who drinks potions faster than arrows hurt, a
breeze or an enderman; a shot with a block or a player in the way it does not let
go); a phantom it does not chase: it stands, for a few seconds after its hit, and
hits it when it dives; a flyer that never comes down (a blaze, a ghast) it leaves
to its guard's hands, and its order goes on; else it goes for it, and hits it once
in reach. It lets go of one that has not hurt it for 15 s or is farther than 25
blocks; one it finds no way to (a skeleton on a pillar) it does not go for again
for 30 s. A hunt or a kill ordered against that very kind fights it as it does.

**Players** are fought only with its `defend_from_players` on (off by default, the
operators' to change): then a player who hurts it, or who hurts its owner within
16 blocks of it where it sees them, is fought as a mob that hurt it; never its
owner, a bot of its owner's, or a player in creative or spectator. Without it, a
player's hits bring its owner a notice, and nothing more.

### Creepers

Never by sword. From 10 to 25 blocks, in sight, with a bow and arrows, it shoots
it, standing (`shoot_creepers`, on by default): an arrow does not light a creeper.
Not from water, with a player on or near the line, at one swelling within 7, at one
with a name (someone cares for it), while something else is hitting it, or at one
its `kill` is after (the kill shoots it, and counts it). Within 10 blocks, or
swelling within 16, it drops what it is doing and runs, sprinting while it has the
food for it (above 6), to anywhere 40 blocks from the creeper (16 in a closed place
with nowhere that far), until the creeper is 16 blocks off. It counts the creepers
it sees, and any within 4 (round a corner they blow all the same), however close:
one in the cave under its feet neither sees it nor hurts it through the rock.
Beyond 7 it counts none it would neither run from nor shoot (past 16 with no bow);
not one with no way on foot to it (for 30 s), nor one that made it run three times
in 90 s without coming for it (for 90 s); a creeper walking at it is danger every
time, never a scare too many. The one it runs from it counts until it is 16 away.
Cornered, its owner is told.

### Badly hurt

With 6 health or less (or poisoned) and something hostile at hand (whatever hurt
it in the last 15 s, within 25 blocks, or a hostile mob within 12 that it sees), it
drops what it is doing and backs off, toward anywhere 40 blocks from it (24 in a
closed place), and does not stop until it makes out nothing hostile within 24
blocks (what it sees or has within 4, and, unseen, what is after it within 8 blocks
up or down; not what walks the cave under it), health or no health: stopping as
soon as it had a little back is how the zombie behind catches up. Cornered, its
owner is told; cornered for 30 s, or backing off for a minute with something still
after it, it stands its ground, for 30 s more. `retreat_when_hurt` turns it off.

### Air and sand

Standing still in deep water (following someone who stopped on a lake, waiting),
it holds jump, as a player does, and keeps its head above it. Under water with 100
of its 300 air left, it drops what it is doing and comes up: straight up, or, with
something over it (ice, a ledge, a cave's roof), along a way to the nearest open
water and up from there; and goes back to what it was doing once its air is 250
again. Drowning with no way up, its owner is told.

Buried (sand or gravel fell on it, and the game hurts it for being inside a block,
or a look once a second finds its feet inside one: gravel that lands where its feet
are leaves its eyes free and hurts nothing, and it could not walk out of it), it
breaks what covers its head, then what fills the space of its feet, before
anything else: with its hands, the best tool it carries for the block, and the time
the block takes. A block it cannot break (bedrock, a protected spawn), or cannot
break in 30 s, is given up, and its owner told.

### Hunger and what it lacks

It eats by itself: with hunger below 10, whatever its health; with health missing
and hunger below 18 (below that, health does not come back by itself); and under
half its health with hunger below 20. It eats the best food it may eat on its own
([Eating](#eating): never banned or harmful food), brought up from its backpack,
and a bite that cannot start now is tried again 5 s later; not while it runs from
a creeper, backs off, comes up for air or fights, unless it is starving. Its owner
is told, once when it happens: hungry (8 or less) with nothing it may eat (and what
it carries that it does not eat on its own, and why; told again only once it has
eaten and gone hungry again), its bow out of arrows, a piece of armor or the tool
in its hand about to break (15% of its uses left).

## Settings

Settings are switches, numbers and choices that say how a bot goes about what it
does.
The easiest way to change them is the [config menu](#the-config-menu); the
commands do the same: `/tachyon settings <who>` lists a bot's, each with its
value and where that comes from, and `/tachyon set <who> <key> <value>` changes
one.

| key | label (in the menu) | group | level | who changes it | default | what |
|---|---|---|---|---|---|---|
| `sprint` | Sprint when walking | Walking | basic | owner, operators | `on` | It sprints when it walks: on flat ground, and not on the last steps. |
| `build_to_move` | Build to get there | Walking | basic | owner, operators | `on` | When there is no way on foot, it places blocks it carries (dirt, stone, planks...) to get where it goes: a bridge over a gap, a tower to climb. ([Getting there](#getting-there)) |
| `build_while_following` | Build when following | Walking | advanced | owner, operators | `on` | When there is no way on foot, it also builds bridges and towers with blocks it carries to keep up with whom it follows. |
| `break_to_advance` | Dig through to get there | Walking | basic | owner, operators | `off` | When there is no way on foot, it digs through blocks on its break list (/tachyon break) to get where it goes. ([Getting there](#getting-there)) |
| `respawn` | Come back after dying | Life | basic | owner, operators | `on` | When it dies, it comes back by itself 2 seconds later, 5 times in 5 minutes at most. When it is off, a bot that dies leaves the game. |
| `come_back` | Come back after a restart | Life | basic | owner, operators | `on` | When the server starts again, it comes back by itself, where it was. |
| `recover_items` | Go back for its things | Life | basic | owner, operators | `on` | Once it is back from a death, it goes back for what it dropped: 2 tries within 6 minutes of the death at most, and never after lava, the void or drowning. |
| `shoot_creepers` | Shoot creepers | Life | basic | owner, operators | `on` | It shoots creepers 10 to 25 blocks away with its bow, by itself. When it is off, it only runs from those that come close. ([Creepers](#creepers)) |
| `retreat_when_hurt` | Back off when badly hurt | Life | basic | owner, operators | `on` | Badly hurt (6 health or less, or poisoned) with something hostile at hand, it breaks off what it is doing and backs off, until it makes out nothing hostile within 24 blocks (a minute at most). ([Badly hurt](#badly-hurt)) |
| `ignore_for_sleep` | Left out of sleeping | Night | basic | operators | `on` | The players skip the night without it: it does not count for the sleeping percentage, and no phantoms spawn because of it. |
| `brain_lite` | Lite brain | Brain | advanced | owner, operators | `off` | Its brain is sent only the core tools, which a small local model chooses from better. ([The brain](#the-brain)) |
| `notices` | Notices to its owner | Brain | basic | owner, operators | `brain` | It tells its owner what nobody asked about (how going back for its things went, a player hitting it, a full backpack, what it could not deal with, a death, an order given by a command that is over) in its own words, whispered to its owner alone (brain: a call to its model), in a fixed line (plain), or not at all (off). ([Talking to a bot](#talking-to-a-bot)) |
| `verbose` | Technical lines | Brain | advanced | owner, operators | `off` | Its owner also gets the technical lines the server's log has about it (what it does by itself, with coordinates), for finding out what went wrong. When it is off, its owner hears only its notices. ([Talking to a bot](#talking-to-a-bot)) |
| `dress_alone` | Put on better armor | Gear | basic | owner, operators | `on` | It puts on better armor it carries, by itself (it looks every 10 seconds). |
| `trash_at_once` | Toss trash at once | Gear | advanced | owner, operators | `off` | It tosses its trash as soon as it picks it up. When it is off, it tosses it only when its backpack is full. ([Tossing](#tossing-and-trash)) |
| `hunt_players` | Fight players by name | Fighting | advanced | operators | `off` | Its brain's attack and kill may go after a player named to them. |
| `defend_from_players` | Defend from players | Fighting | advanced | operators | `off` | It also fights players who attack it or its owner, as it fights mobs that do. When it is off, a player's hits only bring its owner a notice. ([Fighting back](#fighting-back)) |

A bot's value is the first of four layers that has one:

1. **Its own**, set with the menu or `/tachyon set`. It is kept with the world,
   in `<world>/tachyon/bots/<name in lower case>.json`, so it stays when the bot
   leaves and comes back. Setting it back to `default` (Q in the menu) clears it.
2. **The server's default set in game**, by operators, with the menu's "Server
   defaults" or `/tachyon defaults <key> <value>`. It is kept with the world, in
   `<world>/tachyon/defaults.json`, and applies at once to every bot without a
   value of its own. A broken file is moved aside as `defaults.json.bad-<time>`,
   and then there are none.
3. **The server's default in `tachyon.properties`**: `default.<key>=...`
   (`default.sprint=off`). After editing the file, `/tachyon brain reload`.
4. **The mod's default**, in the table.

`/tachyon settings` and the menu say which one a value comes from: `its own`,
`server default, set in game`, `server default, in tachyon.properties` or `the
mod's default`. `/tachyon defaults` lists the server's defaults the same way.

A bot's owner and operators may change its settings; a few are only the
operators' (the table says which). A switch is `on` or `off` (`true`, `false`,
`yes` and `no` are taken too); a number, plain digits within its range, and one
out of it is refused with the range; a choice, one of its options by name
(`notices plain`), and anything else is refused with the options. `/tachyon
settings` lists each by its label with its key in brackets (`Sprint when walking
[sprint]: on (the mod's default). It sprints when it walks...`), and refusals
name a setting by its label (`only operators change "Left out of sleeping"`).

## The config menu

`/tachyon config` opens a chest menu, drawn by the game itself: players need
nothing installed. It shows the bots you may configure (your own; for operators,
every bot) as heads, with what each is doing; operators also get **Server
defaults**. With only one thing to show, it opens that directly;
`/tachyon config <who>` opens one bot.

A bot's page shows its **basic** settings, a row per group (the group's name
first); **Advanced** opens the rest, and **Back** returns. Each setting's item is
named by its label, and says under it what it does, its value, where that comes
from and how to change it:

- a switch is a dye, lime and glinting when on, gray when off: any click turns it
  over;
- a number is a clock, whose stack shows the value when it is a whole one from 1
  to 99 (the text under it always says it): left click +1, right click -1, with
  shift 10 at a time, kept within its range;
- a choice is a name tag, the options listed under it: left click the next
  option, right click the one before, going round;
- **Q** over a setting puts it back to the default (clears the bot's own value,
  or on the Server defaults page the one set in game), and says which default
  that is and its value: `Q: back to the server default set in game (off)`, `Q:
  back to the mod's default (on)`;
- a setting only operators may change shows to others as a barrier, saying so
  by its label.

The Server defaults page (operators) is laid out the same way and changes the
defaults set in game. Every change goes through the same checks as the commands,
takes effect at once, and the page is drawn again; values changed elsewhere show
within a second. A bot's value is kept in its data as `/tachyon set` keeps it
(written within 30 seconds, when it leaves, or at the stop); a server default is
written at once. Nothing can be taken out of the menu or put into it, and your
own inventory is left alone. It closes by itself if the bot leaves the game, or
if you may no longer configure it (it was given to someone else, you are no
longer an operator).

## The brain

`tachyon.properties`, in the server folder:

```properties
# An OpenAI-style chat/completions API: Ollama, OpenAI, OpenRouter, Groq, LM Studio...
api=openai
url=http://localhost:11434/v1
model=qwen2.5:3b
key=
timeout=120
steps=6
per_minute=6
```

Some examples:

| where | `api` | `url` | `model` (for instance) | `key` |
|---|---|---|---|---|
| Ollama on the same machine | `openai` | `http://localhost:11434/v1` | `qwen2.5:7b` | (none) |
| OpenRouter | `openai` | `https://openrouter.ai/api/v1` | `openai/gpt-4o-mini` | yours |
| OpenAI | `openai` | `https://api.openai.com/v1` | `gpt-4o-mini` | yours |
| Anthropic | `anthropic` | `https://api.anthropic.com/v1` | `claude-haiku-4-5` | yours |

The model has to support tool calling. A small local model chooses badly among
many tools: `/tachyon set <who> brain_lite on` sends it only the core ones
(walking, stopping, hunting, gathering, clearing, how it is, what is around),
and not the others: those for killing, attacking, its hands, armor, food and tossing,
`last_death`, and most of those still to come.

Any of `url`, `model`, `key` and `timeout` can be set for one bot only, as
`<name>.<key>` (`Ada.model=...`). The key is read from this file and nowhere
else, and is never said in the game; keep the file private. Empty `url`: the bots
stay silent. After editing it, `/tachyon brain reload`.

## What it costs the server

Everything a bot does runs on the server, so it was measured there: dedicated
servers running this code with no content mods (NeoForge 21.1.248, i7-12700
VMs), before 0.2.0's reflexes and its new hunt. A tick has 50 ms (20 a second)
before the server lags:

| bots | doing | tick, median | tick, worst 1% | the bots' share |
|---|---|---|---|---|
| 100 | following a player | 16 ms | 24 ms | 4.9 ms |
| 400 | following a player | 17 ms | 40 ms | 8.2 ms |
| 100 | clearing a 40×3×40 block (4,800 blocks) | 8–12 ms | 46 ms at the busiest | 3.5–4.5 ms |

With 0.2.0 (a 6-core VM; the bots brought in together on a stone floor, standing
close to each other; samples of 15 to 20 s; the whole tick from `/tick query`, the
bots' share and its worst tick from `/tachyon stats`):

| bots | doing | tick | the bots' share | its worst tick |
|---|---|---|---|---|
| 100 | standing, no mob about | 9–11 ms | 5.9–7.2 ms | 14–22 ms |
| 100 | standing at night, 640 to 705 mobs spawned around them | 17–25 ms | 2.6–3.7 ms | 14–21 ms |
| 100 | standing with bows among 30 creepers 18 to 24 blocks off (arrows do not hurt them) | 10–11 ms | 6.8–7.0 ms | 16–28 ms |
| 100 | told to hunt cows where there are none, going out looking | 8–12 ms | 5.1–6.9 ms | 15–23 ms |
| 500 | the same | 16–21 ms | 15 ms | 81–82 ms |
| 100 | hunting 200 cows packed in an 80×80 pen, 2 each, for two minutes | 11–16 ms | 5.6 ms | 31 ms |

About 0.02–0.07 ms of the tick and about 2 MB of memory per bot. Route searches
and the model's answers run on threads of their own, never on the tick;
`/tachyon stats` says how many searches wait for a thread: at most 17 with 100 bots
in all of the above, but with 500 told to hunt at once 45 to 200 on average and up
to 400: one routes thread (a quarter of the cores) does not keep up with 500 walks.
What a bot does by itself (armor looked at every 10 s, trash on a pickup, a bite or
a few hits under way, a trip back for its things after a death, hits from players
counted, and [the reflexes](#what-it-does-by-itself): a look around for hostile
mobs every 10 ticks, none on a peaceful server) costs next to nothing while there
is nothing to do. The reflexes, timed one by one with 100 bots standing: about
2 µs a bot a tick for the six of them, the timing's own cost included, no more than
in the version before (1.9–2.2 µs against 2.1–2.6 µs); at night, while some of them
fight the mobs that come, 1.8 to 4.8 µs (the version before, among fewer mobs, 2.0
to 2.9). `/tachyon stats` cannot tell them apart from its own noise:
6.6 ms a tick for the 100 standing, with this version and the one before alike
(means of 18 samples of 20 s, the two builds taking turns; single samples from 5.9
to 7.4 ms). Among the 30 creepers the bots ask whether one can walk to them about 80
times a second, a bot asking for those it is standing by; the version before asked
about 200 times. In the pen, 81 of the 100 hunters were done within two minutes and
the rest stopped hurt, crowded (the version before did the same). In a fight they
work: 100 bots with iron swords among 30 zombies (all dead within 10 s) cost 3.1 ms
a tick over that minute. What a bot's abilities do in other entities' ticks (a hit
taken, a pickup) is in the whole tick, not in the bots' share.

Gathering, measured the same way (samples of 20 s, the 0.3.0 dev server):

| bots | doing | tick | the bots' share | its worst tick |
|---|---|---|---|---|
| 100 | standing, no mob about (this version and the one before taking turns, 6 samples each: 5.3–5.7 ms before) | 8–10 ms | 4.7–5.5 ms | 16–19 ms |
| 100 | gathering 64 dirt each on a 112×112 grass field | 11–13 ms | 6.2–7.7 ms | 21–33 ms |
| 100 | gathering what is nowhere around, out looking over the world's own ground | 12–18 ms | 4.3–5.0 ms | 17–29 ms |

A look around for blocks is a sweep of the chunk sections within 16 blocks, a
section with none of the kinds skipped whole; a bot looks when what it knows of
is spent (every half second at most, every 2 s while out looking, each on a tick
of its own), and no more than 4 bots look in one tick. The searches of the 100
out looking took 6 to 10 ms each on their thread, 3 waiting at most. What was
added to a bot standing idle (a look once a second for its feet inside a block) is
a block read a second. A busy modpack leaves less room than a plain server:
measure yours with `/tachyon stats` and `/tick query`.

Getting there, as it is now, against 0.3.0 (the same 6-core VM, 100 bots told at
once, the bots' share from `/tachyon stats` in samples of 10 s, 20 s standing;
another test server ran on the VM meanwhile, so a sample alone may be 0.6 ms off
either way):

| 100 bots | 0.3.0 | now |
|---|---|---|
| standing (12 samples each, the builds taking turns) | 3.0–4.2 ms, 3.7 on average | 2.5–4.5 ms, 3.9 on average |
| to a place 325 blocks off, over flat stone | all there in 60–70 s; 4.8 ms, 100 searches, 179,000 tiles | all there in 70 s; 4.6 ms, the same searches |
| to a place 390 blocks off, over forest, hills and a lake | 69 there in 161 s, 29 out of time on their one route, 2 stuck; 4.0 ms, 185 searches, 2.6 million tiles | all there in 161 s; 4.2 ms, 203 searches, 2.7 million tiles |
| following a bot that goes 320 blocks | 5.4 ms | 4.9 ms |
| over a canyon 4 wide, 16 cobblestone each | (none could) | all there in 20 s: 11 bridged it, the rest walked over their bridges; 4.4 ms |
| through a stone wall 3 thick, a pickaxe each, `break_to_advance` on | (none could) | all there in 20 s, 42 blocks dug by 29 of them; 5.2 ms |
| out of a fenced pen through one closed gate | (a gate was a wall) | all out in 20 s; 4.6 ms |

The bots' share is the same while they walk, build or dig: a block to place or
dig is looked at only by a bot whose route asked for one. The searches cost the
same too; the legs after the first may look at 40,000 tiles instead of 20,000,
and a trip searches a leg again after 90 s or when stuck, where 0.3.0 gave up.
One bot alone: 390 blocks over forest, hills and a lake with nothing loaded
ahead, 110 s and 3 legs; 340 blocks of open sea, 110 s, 7 of its 8 legs swum
straight on without a search.

## Where to use it

On your own servers, or on servers whose owners explicitly allowed your bots.
What players say to a bot is sent to the AI provider you configured, the chat is
untrusted input to the model, and every answer costs whoever owns the key.

## Building

```bash
./gradlew build       # build/libs/tachyon-<version>.jar, and the tests
./gradlew runServer   # a dedicated server to try it on, in run/
```

To teach the bots something new, see [Adding an ability](docs/adding-an-ability.md).

## Acknowledgements

- **[Masurium](https://github.com/CharquiPayload/masurium)**, where Tachyon comes
  from: its path finder and the way its client bots walk, follow and find their
  footing are the bots' legs here.
- **[Carpet](https://github.com/gnembon/fabric-carpet)** (MIT), whose fake players
  showed how a server can host a player nobody plays: a connection that goes
  nowhere, the body ticked by the server, falls judged there. No code of Carpet's
  is used.
- **[Baritone](https://github.com/cabaletta/baritone)** (LGPL-3.0), whose goals
  as conditions ("within 2 of it", "this column") the path finder borrows as an
  idea. No code of Baritone's is used.

Tachyon is an independent project, **not an official Minecraft product: not
approved by or associated with Mojang or Microsoft**.

## License

[MIT](LICENSE) © 2026 CharquiPayload
