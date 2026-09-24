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

**Early days (0.1.0).** It walks, follows, hunts, clears areas and talks; most of
what a player does is still to come.

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
operator can hand it to someone else with `owner`. Only operators bring bots in,
since every bot costs the server something. **In the chat, a bot listens only to
its owner**, not even to operators: every answer is a call to a model someone pays
for.

## Talking to a bot

Name it in the chat: `Ada, come here`, `Ada hunt three cows`, `what do you see,
Ada?`. It answers in the chat, in the language it was spoken to in, and does what
it can with its tools: come to you, follow, go somewhere, stop, hunt, clear a
box, tell how it is, look around. When something it was asked is over (done, or
given up), it says so, in its words. Each player can speak to bots a few times a
minute (`per_minute`).

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

The model has to support tool calling. Any of `url`, `model`, `key` and `timeout`
can be set for one bot only, as `<name>.<key>` (`Ada.model=...`). The key is read
from this file and nowhere else, and is never said in the game; keep the file
private. Empty `url`: the bots stay silent. After editing it, `/tachyon brain reload`.

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
