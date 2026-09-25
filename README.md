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

**Early days (0.2.0).** It walks, follows, hunts, clears areas and talks; it comes
back when it dies and when the server restarts; its settings have an in-game
menu; most of what a player does is still to come. What is done and what comes
next: [ROADMAP.md](ROADMAP.md).

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
| `/tachyon hunt <who> <mob> [count]` | owner, operators | kills that many of a mob each (every one around without a count) and picks up the drops |
| `/tachyon clear <who> <from> <to>` | owner, operators | breaks every block in the box, top layer first, with the right tools |
| `/tachyon tell <who> <words>` | owner, operators | says something to a bot, as if in the chat |
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

An order given to one bot is told back when it is over (done, or given up): a line
to whoever gave it. Orders to many bots are not, or the chat would flood; `list`
shows how each goes.

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
it can with its tools: come to you, follow, go somewhere, stop, hunt, clear a
box, tell how it is, look around. When something it was asked is over (done, or
given up), it says so, in its words. Each player can speak to bots a few times a
minute (`per_minute`).

## Death, restarts and the night

**When a bot dies**, it comes back 2 seconds later, as a player who pressed
"respawn" does: at its bed or respawn anchor if it set one and it is still
there, else at the world's spawn; whole (health, food, air, no fire, no
effects), with whatever it was doing dropped, and still the same bot (its owner,
settings, data and brain). What it carried stays where it died. An order given to
it in those 2 seconds, and what is said to it, it takes up once it is back; a bot
that follows it waits, and follows it again. A bot that dies
over and over (in lava, or where something kills it as soon as it is back) comes
back 5 times in 5 minutes at most: the 6th time, it leaves the game instead. With
`respawn` false it leaves the game when it dies, as it did in 0.1.0. Either way
its owner, if online, gets a line: `[tachyon] Ada died (Ada was slain by Zombie)
and is back at 12 64 -30`, or `... and left: 5 deaths in 5 minutes`. A bot that
left is brought back with `spawn`, whole, as a respawn would bring it.

**When the server stops**, the bots in the game are recorded with the world
(`<world>/tachyon/roster.json`: name, dimension, position, rotation), and once it
has started again each one comes back where it was, its owner's as before, unless
its `come_back` is false. A server that crashes records them too, as it stops. A bot removed before the stop does not come back; one
whose dimension is gone (a mod's, removed) comes back at the world's spawn. A
broken roster is moved aside as `roster.json.bad-<time>`, and nobody comes back.

**At night**, with `ignore_for_sleep` (the default), the bots are not players to
the night: the players skip it by sleeping without them, and the "n/m players
sleeping" line counts only the players; and no phantom is spawned because of a
bot (phantoms come for a player who has not slept for three days, and once there
they attack any player near, bots too). With it false a bot counts as any player
does, and the night is skipped only if enough bots sleep too. It is
the operators' to change, since it changes the night for everyone.

## Settings

Settings are switches and numbers that say how a bot goes about what it does.
The easiest way to change them is the [config menu](#the-config-menu); the
commands do the same: `/tachyon settings <who>` lists a bot's, each with its
value and where that comes from, and `/tachyon set <who> <key> <value>` changes
one.

| key | label (in the menu) | group | level | who changes it | default | what |
|---|---|---|---|---|---|---|
| `sprint` | Sprint when walking | Walking | basic | owner, operators | `true` | whether it may sprint when walking |
| `respawn` | Come back after dying | Life | basic | owner, operators | `true` | whether it comes back by itself when it dies (5 times in 5 minutes at most); false: it leaves the game |
| `come_back` | Come back after a restart | Life | basic | owner, operators | `true` | whether it comes back by itself, where it was, when the server starts again |
| `ignore_for_sleep` | Left out of sleeping | Night | basic | operators | `true` | whether the players skip the night without it (it does not count for the sleeping percentage) and no phantoms spawn because of it |
| `brain_lite` | Lite brain | Brain | advanced | owner, operators | `false` | whether its brain is sent only the core tools, for a small local model ([The brain](#the-brain)) |

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
   (`default.sprint=false`). After editing the file, `/tachyon brain reload`.
4. **The mod's default**, in the table.

`/tachyon settings` and the menu say which one a value comes from: `its own`,
`server default, set in game`, `server default, in tachyon.properties` or `the
mod's default`. `/tachyon defaults` lists the server's defaults the same way.

A bot's owner and operators may change its settings; a few are only the
operators' (the table says which). A switch takes `true` or `false` (or `on`,
`off`, `yes`, `no`); a number, plain digits within its range, and one out of it
is refused with the range.

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
- **Q** over a setting puts it back to the default (clears the bot's own value,
  or on the Server defaults page the one set in game);
- a setting only operators may change shows to others as a barrier, saying so.

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
many tools: `/tachyon set <who> brain_lite true` sends it only the core ones
(walking, stopping, hunting, clearing, how it is, what is around; for now that is
every tool there is, and the tools still to come will not all be core).

Any of `url`, `model`, `key` and `timeout` can be set for one bot only, as
`<name>.<key>` (`Ada.model=...`). The key is read from this file and nowhere
else, and is never said in the game; keep the file private. Empty `url`: the bots
stay silent. After editing it, `/tachyon brain reload`.

## What it costs the server

Everything a bot does runs on the server, so it was measured there: dedicated
servers running this code with no content mods (NeoForge 21.1.248, i7-12700
VMs). A tick has 50 ms (20 a second) before the server lags:

| bots | doing | tick, median | tick, worst 1% | the bots' share |
|---|---|---|---|---|
| 100 | following a player | 16 ms | 24 ms | 4.9 ms |
| 400 | following a player | 17 ms | 40 ms | 8.2 ms |
| 100 | hunting 200 cows (all dead in about 16 s) | 13 ms | 24 ms | about 5 ms |
| 100 | clearing a 40×3×40 block (4,800 blocks) | 8–12 ms | 46 ms at the busiest | 3.5–4.5 ms |

About 0.02–0.06 ms of the tick and about 2 MB of memory per bot. Route searches
and the model's answers run on threads of their own, never on the tick. A busy
modpack leaves less room than a plain server: measure yours with
`/tachyon stats` and `/tick query`.

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
