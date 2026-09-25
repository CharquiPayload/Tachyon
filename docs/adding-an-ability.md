# Adding an ability

An ability is one thing the bots can do: walk, hunt, clear a box, talk. Each
lives in files of its own and is plugged in by one line in `Abilities.java`.
`Bots` and `Brain` reach the abilities only through `Abilities` (the commands,
tools and settings gathered there, and the hooks called from there), so adding
one does not mean editing them. The other way round, an ability uses what
`Bots` and `Brain` offer: the command helpers, the orders, the legs, a bot's
data, the brain's notices.

Five exceptions, on purpose, because what they decide lives in the core:
Walking's `sprint` setting is read in `Bots.canRun`, where the legs (routes,
keys, doors) are; the legs also ask `Scaffolding` and `Tunnelling` whether a
search may plan building or digging (their settings, the blocks it carries, its
break list) and have them place and dig the blocks a route asks for, in
`Bots.steer`; `Bots.died` asks `Respawning.staysDead` whether a bot that
died comes back (its `respawn` setting, and the deaths it counts), and
`Respawning.died` and `Respawning.where` for the words its owner hears, since a
bot's coming and going is there; `Tools.offered` reads Talking's `brain_lite`,
since which tools a brain is sent is decided there; and what the core must tell
a player (a death, an order's end, a brain that could not think) goes through
`Notices.tell`, whose settings (`notices`, `verbose`) say how.

This page is for whoever adds the next one. Read `Walking.java`, `Hunting.java`
and `Hunt.java` alongside it: they are the smallest complete examples.
`Gathering.java` and `Gather.java` are one that breaks blocks by itself.

## The files

- **`<Name>.java`** (in `src/main/java/tachyon`, package `tachyon`): the
  ability, a class that `implements Ability`. Name it for what the bot does,
  as a plain word: `Walking`, `Hunting`, `Farming`.
- **A job**, if it does something that takes time (`Hunt.java`, `Clear.java`):
  a class that `extends Job`, in a file of its own.
- **Tests**, in `src/test/java/tachyon`, for whatever can be tested without a
  game: a parser, a plan, a choice made from plain numbers. The tests have
  Minecraft's classes but no game: nothing that needs the registries (an
  `EntityType`, a `Block`) or a server.

Then the one line, in `Abilities.ALL`:

```java
static final List<Ability> ALL = List.of(
        new Walking(),
        ...
        new Looking(),
        new Farming());        // <- yours
```

The order of that list is the order of the subcommands under `/tachyon`, of
the tools in what the model is sent, and of the reflexes in a bot's tick. Add
yours at the end unless there is a reason not to.

## The hooks

`Ability`'s hooks are all optional (default methods that do nothing). There
are two kinds.

**What it brings**, asked once as the server starts (commands: again on
`/reload`). They do not run on the server's thread, and nothing guards them: a
mistake there (two tools of one name, two subcommands of one name, a setting
key that is not lower case) stops the server's start, where it is seen at once.

| hook | when | for |
|---|---|---|
| `events(bus)` | the mod is made | its own handlers of the game's events: `bus.addListener(ServerStartedEvent.class, e -> ...)`, or `bus.register(this)` for `@SubscribeEvent` methods |
| `tools(tools)` | the abilities are gathered | the brain's tools |
| `settings(settings)` | the abilities are gathered | the settings it declares |
| `commands(tachyon, context)` | the server registers its commands | subcommands under `/tachyon` |

**What it does for a bot**, on the server's thread. One that throws is logged
(once a minute at most) and skipped; it does not stop the others, crash the
server or keep a bot's data from being written. That is a safety net, not a
way of working: a hook says what went wrong in words, where someone will see
them.

| hook | when | for |
|---|---|---|
| `joined(bot)` | a bot came in, its data already read | picking up what it kept |
| `left(bot)` | a bot is leaving, before its data is written | putting away what it keeps |
| `died(bot, cause)` | its body died, its order as it was: then it comes back 2 s later (a new body, its order dropped; one given to it while dead is carried out then), or leaves (`left` follows) | what killed it |
| `hurt(bot, source, amount)` | it lost health | being hit: by whom, how much |
| `tick(bot, now)` | every tick, before its job thinks | a reflex |
| `act(bot, now)` | every tick, after the walk's keys, before the job's hands | a reflex's hands |
| `ordered(bot)` | it was given an order | what it does now |
| `over(bot, how)` | its order is over by itself, after whoever gave it was told | what it did |
| `heard(bot, from, text)` | words in the chat name it, before its brain | stop words, who it listens to |
| `rules(bot, rules)` | its brain's turn starts | lines for its instructions |
| `state(bot, parts)` | its brain's turn starts | parts of the state line |

In `left`, `bot.leaving()` says why (`REMOVED`, `DIED`, `STOPPING`); the bot
is still among `Bots.all()`, and its order is as it was (its job, where it
went), so what it was doing can be kept.

**The body changes.** A bot that dies and comes back is the same `Bots.Bot` (its
data, its slots, its brain), with a new `BotPlayer`: `bot.body` is another
object after a respawn, as a player's is. Read `bot.body` where it is used, and
never keep it (in a job's field, a slot, a record): what is kept is a corpse. A
`ServerPlayer` kept for another reason has the same trouble when that player
respawns: whom a bot follows is kept up to date by `Bots` (from NeoForge's
`PlayerEvent.Clone`, which a respawn posts, a bot's too); an ability that keeps a
player does the same in a handler of its own, or keeps the UUID instead.

A death may come in the middle of the bot's own tick: a job's hit that thorns
pays back, a reflex's. It drops the bot's order there and then, so code that
called what killed it finds `bot.job` null afterwards, and must look before it
goes on (as `Bots.pilot` does).

For anything else of the game's (a block placed, an item picked up, a
player's death), an ability registers handlers of its own in `events`.
`Bots.of(entity)` says whether an event's entity is a bot, and which.

## Commands

```java
@Override
public void commands(LiteralArgumentBuilder<CommandSourceStack> tachyon, CommandBuildContext context) {
    tachyon.then(Commands.literal("farm")
            .then(Bots.who()
                    .then(Commands.argument("from", BlockPosArgument.blockPos())
                            .then(Commands.argument("to", BlockPosArgument.blockPos())
                                    .executes(Farming::farm)))));
}

private static int farm(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
    List<Bots.Bot> them = Bots.find(c);
    ...
    Bots.Order by = Bots.order(c.getSource(), them);
    for (Bots.Bot p : them) orderFarm(p, ..., by);
    return Bots.told(c, them, "farming " + ...);
}
```

What `Bots` has for commands (all package-visible):

- `Bots.who()`: the `<who>` argument, a bot's name, a pattern (`Miner*`, `*`)
  or a selector, with the bots' names suggested.
- `Bots.find(c)`: the bots `<who>` names **that the source may order**:
  operators any, anyone else their own. When there are none it has already said
  why; return 0.
- `Bots.told(c, them, "doing what")`: the one line of answer, by name for one
  bot, by count for more.
- `Bots.say(source, text)` and `Bots.fail(source, text)`: a line, or a
  refusal, prefixed `[tachyon]`.
- `Bots.operator(source)` and `Bots.mayOrder(source, bot)`: who may do what.
  A command that only operators may use says `.requires(Bots::operator)`.
- `Bots.order(source, them)`: whom to tell when what the command started is
  over. Only for an order to one bot: a crowd's reports would flood whoever gave
  it, and `list` shows how each goes.

`context` is for arguments read from a registry
(`ResourceArgument.resource(context, Registries.ENTITY_TYPE)`).

A subcommand is added to `tachyon` only: never `.requires` on `tachyon`
itself. One whose name is taken (by another ability, or the mod's own `spawn`,
`remove`, `settings`, `set`, `defaults`, `config`, `owner`, `brain`, `list`,
`stats`) stops the start.

Answers are plain and short, in the words the rest of the mod uses: "hunting
cow, 2 each", "no bot of yours matches X", "a box of 200000 blocks: 100000 at
most".

## Orders and jobs

An order replaces whatever the bot was doing. The ones there are (`orderGoto`,
`orderFollow`, `orderStop`, `orderHunt`, `orderClear`) are static methods in
`Bots`, callable from anywhere. A new one that starts a job is made with
`Bots.orderJob`, in the ability's own file:

```java
static void orderFarm(Bots.Bot p, Farm.Field field, Bots.Order by) {
    Bots.orderJob(p, new Farm(field), "farming " + field.box(), by);
}
```

`orderJob` ends the job it had (its `end` lets go of what its hands did), stops
the walk, sets `doing`, and remembers `by`: who is told when the job is over.
Every order tells the abilities (`ordered`); every order over by itself tells
them too (`over`), after whoever gave it.

**A standing order** (an escort, a guard, an errand to come back to) is
`Bots.orderStanding(p, job, doing, by)`. The orders given after it do not end
it: they are detours. Its job is set aside, and taken up again when the detour
is over by itself (arrived, hunted, given up). `stop` ends it, and so does
another standing order, and so does its own job, over by itself.

A `Job` works a tick at a time (see `Job.java`):

- `think(p, now)`, before the walk: where to go (`Bots.plan`, a search on a
  thread of its own), and whether it is over. It returns false once it is
  over, having said how in `doing` (through `Bots.halt(p, "farmed 12 wheat")`).
  Then whoever gave the order is told that line: in the chat, in the bot's own
  words if it was said to it; else as a line to them.
- `act(p)`, after the walk pressed its keys: the hands. It may take the keys
  over for the last steps (`Job.walkStraight`). It is skipped while a reflex
  holds the hands; a job that uses its hands in `think` too (Clear's first
  stroke) asks `Bots.handsFree(p)` first.
- `status()`: what `list` and the brain's `status` show while it lasts.
- `end(p)`: it ends, **or is set aside for a while** (a reflex took the body, a
  detour from a standing order). Let go of whatever the hands were doing (a
  block half broken, a claim on a shared area, a target). A job set aside is
  taken up again as it is: `think` runs again from its fields, and must cope
  with a gap (its target gone, no route).

`Job.hold(p, score)` puts the best item for something in the hand, from
anywhere in the inventory; not while a reflex holds the hands. `Gear.weapon` is
the score for hitting (a weapon before any tool, then damage per second), and
`Gear.toHand(p, slot)` brings one slot's item into the hand: selected in the
hotbar, or brought up from the backpack into the hotbar slot Masurium's rule
gives up (for a weapon, the weakest weapon there if the new one is better; else
an empty slot; else, for a weapon, the smallest stack of something of little
use, not a tool, weapon, bow, food or torch; else the one in hand). A hit waits
a tick after the hand changed, as a player's: the game gives the new item its
damage and starts its charge again in the body's own tick, after the hands; hit
at once, the old item's damage lands. `Job.swapped(p, score)` is `hold` saying
whether the hand changed, to return on. `Gear` has the rest of what a bot
carries: an item's id in words and back (`Gear.id`, `Gear.item`), counts,
whether something fits.

**Breaking blocks.** `Clear.reachOf(pos)` is the goal of a walk to within a
player's reach of a block, and `Clear.sight(p, level, pos)` what a click at it
would hit from where the bot stands (null out of reach): another block in front
of it is what the click breaks. The stroke is the server's own for a player
(`gameMode.handleBlockBreakAction`: start, then stop once the block's time is
up), so protections and the tool's wear apply as to anyone; `Gather` has it in a
few lines, a hand that changed mid-stroke starting it again. A job that breaks
blocks **by itself** (not a box a person named) never breaks a build:
`PlacedBlocks.byPlayer(level, pos)` says whether a player placed the block there
(the server keeps it, per level, with the world), and `Gather.partOfBuild` is the
rest of the rule (a block that touches a building block or a placed one; a log
whose tree is none). A brain's tool that breaks a box looks first with
`PlacedBlocks.count(level, a, b)`, as `clear`'s does, and sends a person to the
command when there are any.

**Going out looking.** With none of what it wants in sight, a job does not stand
still: `Legs` walks it out in legs of 48 blocks (300 blocks or 3 minutes at most,
twice an errand, turning right when three legs get it nowhere), the job looking
around as it walks and calling `legs.found()` once something is in sight; its
`step` says, when the search is over, how far it looked, for the job's last line.

**What it goes after, it sees.** A job that picks a target (a mob to hunt, a
player to throw to) takes only what a player in the bot's place would know of:
in sight from its eyes, or within 4 blocks (`Hunt.noticed(b, e)`,
`Hunt.nearestSeen(b, found, avoid)` for the nearest of a lookup, the sight tried
on a few only). A lookup of the level's entities goes through walls. And a job
that looks around every so many ticks does it on a tick of its own for each bot
(`Hunt.due(p, now, every)`): a hundred bots told at once must not all look in the
same tick.

`Bots.plan(p, to, goal, options, doing)` searches with options of its own
(`Route.Options`: a longer fall, building, breaking); the other `plan`s use
the walk's, `Bots.walkOptions(p, build, nodes)`: partial routes, as long a fall
as its health allows (`Bots.safeFall`, Masurium's: 3 blocks, a block more for
every 4 health, 12 at most), and digging through its break list when its
`break_to_advance` is on. A route searched with building or digging allowed is
walked with those steps: the legs place the block a bridge or a tower needs, and
dig the one in the way, before walking on. `Bots.arriveWithin(p, slack)`, after
a `plan`, is how close to the last point the walk ends (1.4 blocks unless asked).

What the legs do for every walk, a job's too: a tile the body failed to get into
(six jumps without getting closer to it) is left out of the bot's searches for
90 s (`StuckSpots`), so the next search finds another way; a job's walk stops
then, saying so, and the job searches again. `Bots.blocked(p, why)` is the same
for a step the walk cannot take (no block to build with, no permission to dig).
A goto is a trip (`Trip`, `path/Leg.java`): walked a leg at a time and judged as a
whole; a job that goes far walks stretches of partial routes and judges its own
progress, as `Recover` does.

## Tools

A tool is what the brain may call: a name, what the model is told it does and
takes, and a handler that runs it.

```java
@Override
public void tools(Tools tools) {
    tools.add(new Tool("farm", "Harvest and sow again the crops in a box.",
            List.of(Tool.param("x1", "integer", "A corner's X"), ...,
                    Tool.optional("crop", "string", "Only this crop: wheat, carrots...")),
            call -> {
                JsonObject a = call.args();
                ...
                orderFarm(call.bot(), field, call.order());
                return "started farming " + ... + "; nothing harvested yet, it takes a while";
            }));
}
```

`Tool.Call` has the bot (`call.bot()`), what the model gave (`call.args()`, a
`JsonObject`), who spoke (`call.speaker()`, a `ServerPlayer`, null for the
console or someone gone), their name (`call.speakerName()`), what they said this
turn (`call.words()`: null when nobody spoke, in a turn for news or a notice,
where the speaker is only whom the bot answers), and the order for what the tool
starts (`call.order()`): pass it on to the `order...` method, and whoever spoke
is told how it went when it is over, in the bot's words. A tool that may do
something only because a person asked (eat a banned food) looks at
`call.words()`: its brain alone, or a notice, did not ask.

What the handler returns is words for the model, and the model believes them:

- **Something that takes time says it started, and is not done yet**: "started
  walking to 10 64 -3, 12 blocks away; not there yet". The model is told its
  tools start things; a result that sounds done makes it say so.
- **Something it cannot do says why, in words**: "there is no mob called
  dragn". A handler that throws is not a crash: the model is told "that could
  not be done: " and the exception's message, which is rarely good words.
- **The arguments are the model's**, and a model makes mistakes: a key it was
  told is required may be missing, a number may come as a string.
- **Never a secret** (a key, another player's coordinates they did not share):
  the model's answers are said in the chat.

More a tool may have, set as it is made:

- `.rule("...")`: a line for the brain's instructions while it has the tool,
  when the description cannot say when or how to use it. Paid for on every
  turn: short.
- `.core()`: one of the core tools, those a bot with a lite brain
  (`brain_lite`, for a small local model) is sent too; a full brain is sent
  every tool. A small model chooses badly among many: a tool is core when a bot
  is of little use without it, and most new ones are not.
- `.offeredWhen(bot -> ...)`: offered only to some bots (a setting, a
  permission). A tool not offered is not sent, and a call of it that turn (the
  model saw it in an earlier one, or guessed its name) is answered "there is no
  tool ...": the model neither sees nor runs it. Asked on the server's thread as
  each turn starts. The same holds for a lite brain and the tools that are not
  core.
- `Tool.later(name, description, params, call -> future)`: a tool whose
  answer takes long to find (a search over many blocks). It starts on the
  server's thread, takes what it needs of the world there (a `SnapshotWorld`),
  and completes the future from a thread of its own; the brain waits for it on
  its own thread, never the server's.

The tools are sent to the model in `Abilities`' order. A tool changes what the
model is sent, and so how every bot behaves: its name and description are
behaviour, as much as its handler. `ToolsTest` holds the eight core tools to
the byte (`core-tools.json`: those of 0.1.0, with hunt's `mob` mended, hunt
made Masurium's since, and its `count` saying when it stops); new tools come
among them without touching them.

## The brain

Besides its tools, an ability can reach a bot's brain (`Bots.brain(p)`):

- **`Notices.say(p, kind, text)`**: something its owner is to hear of that
  nobody asked about (how going back for its things went, a player hitting
  it). Its `notices` setting decides how: its brain says it in its own words
  (one call to its model, no tools, as it tells how an order went), whispered
  to its owner alone, a plain line to its owner, or nothing. It takes care of the brakes: each `kind` once
  every 10 minutes per bot (put the detail in the kind when two cases must both
  be told: `hit:Steve`, `hit:Alex`), and only while its owner is in the game.
  `text` says what happened, about the bot without naming it and with the
  numbers, as a finished order's line does ("got back 12 of the 14 items it
  dropped when it died at 10 64 -3"), with no period at the end: its owner may
  read it as it is. This is the way for a body to speak up; the next one is for
  what the brain is to act on.
- **`brain.notice(text)`**: something it noticed (hungry, hurt, a reminder
  due), in words for the model: what happened, and what it may do ("You are
  hungry (food 5/20): eat if you carry food; tell your owner only if you
  cannot."). The brain is told with its tools, on its owner's behalf: what it
  starts is told to its owner when it is over. **Every notice is a paid call
  to a model**: send one when it matters, and not again for the same thing for
  a while (keep when in the bot's slot, see below). A few wait while it thinks.
  Its owner chose how much it hears unasked (`notices`): with `off` send none,
  and with `plain` say it with `Notices.say` instead (`Tossing` does both).
- **`Notices.tell(p, to, text, technical, inItsWords)`**: what the mod must tell
  a player once, when it happens (a death, an order given by a command that is
  over, a brain that could not think), without `say`'s rest; to its owner or to
  whoever gave the order. Its `notices` decide how, as for `say`; with its
  `verbose` on, the technical line goes as it is.
- **`Notices.technical(LOG, p, text)`**: a line about what a bot did (a reflex
  taking the body, what it gave up), with its coordinates, the bot's name first:
  it goes to the server's log through the ability's own logger, and, with the
  bot's `verbose` on, to its owner too. Use it for every line of the log about one
  bot; players read no other line of the mod's but notices and commands' answers.
  On the server's thread (from a brain's thread, through `server.execute`).
- **`rules(bot, rules)`**: lines added to its instructions on every turn
  (standing orders, a personality). Short, in plain words for the model.
- **`state(bot, parts)`**: a few words each, added to the state line at the
  head of every turn (a mood, what it fears).
- `brain.failure()`: why its last turn failed, or null.

Every word added here is sent, and paid for, on every turn of every bot.

## Settings

A setting is a switch, a number, or a choice among a few named options, that
says how a bot goes about what it does, with a value of its own for each bot.
Declare it, with the words the config menu shows it with:

```java
static final String TORCHES = "torches";

@Override
public void settings(Settings settings) {
    settings.bool(TORCHES, true, "It lights the tunnels it digs with torches.", Settings.Who.OWNER)
            .label("Light tunnels").group("Mining").basic();
    settings.number("follow_gap", 3, 1, 10, "How far it keeps from whom it follows, in blocks.", Settings.Who.OWNER)
            .label("Following distance").group("Walking").advanced();
    settings.choice("tunnel", "straight", List.of("straight", "stairs", "spiral"),
            "How it digs down: straight, in stairs or in a spiral.", Settings.Who.OWNER)
            .label("Way down").group("Mining").advanced();
}
```

and read it where the code decides, on the server's thread:

```java
if (Settings.bool(p, Mining.TORCHES)) ...
double gap = Settings.number(p, "follow_gap");
if (Settings.choice(p, "tunnel").equals("stairs")) ...
```

A choice is for more than two ways of doing one thing; its options are lower
case words, as a player types them (`/tachyon set Ada tunnel stairs`), kept in
the bot's data by name, and the menu goes through them in the order declared.
Two options are a switch.

- **Keys** are lower case, digits and `_`, and never change once released:
  they are in the bots' saved data, in `defaults.json` and in
  `tachyon.properties`. `url`, `model`, `key` and `timeout` are the brain's, and
  refused.
- **The description** is full sentences written for players, a capital first and
  a period at the end (`Settings.check` refuses anything else): what the bot does,
  for a switch what it does when it is on, and, when that is not plain, what
  happens when it is off ("When it is off, it only runs from those that come
  close."). Not "whether it ..." fragments, nor "false: ..." as a command line
  says it. `/tachyon settings` shows it after the value, and the menu under the
  setting's name. Players read a switch as `on` or `off` everywhere
  (`Setting.words`); refusals name a setting by its label (`Setting.named`).
- **Who**: `OWNER` (its owner and operators may change it) for what concerns
  only the bot; `OPERATOR` for what concerns the server (what it may break,
  whom it may fight). The menu shows an operators' setting to anyone else as
  locked.
- **The label** is the setting's name in the menu: a few words a player would
  say, 32 characters at most ("Come back after dying", not "respawn toggle").
- **The group** puts it in a row with the settings of the same kind: a word or
  two, spelled exactly as the others of that kind spell it ("Walking", "Life",
  "Night", "Brain", "Gear", "Fighting"), or it is a group of its own. Groups are shown in the order
  their first setting was declared, that is the abilities' order.
- **The level**: `.basic()` for what most owners will want to change, shown
  first; `.advanced()` for the rest, behind the menu's "Advanced" button. The
  mod is meant to be very configurable without drowning players in options:
  when in doubt, advanced.
- A setting without a label, a group or a level is a mistake, said as the server
  starts (`Settings.check`, from `Abilities`).
- **The value** a bot has is the first of four layers that has one: its own
  (`/tachyon set`, the menu), the server's default set in game
  (`/tachyon defaults`, the menu's Server defaults: `<world>/tachyon/defaults.json`),
  the server's default in `tachyon.properties` (`default.<key>=...`), the
  declared default. A number read is always within its range.
- A read is a lookup in the bot's data and, when it has no value of its own,
  one in the defaults set in game, then one in `tachyon.properties`' defaults,
  parsed once and kept until `/tachyon brain reload`: cheap enough for every
  tick.
- A change of a setting is not announced: code that must act when one changes
  (a count taken again) looks at it every so often, as `Sleeping`'s reflex does.

## Reflexes

`tick(bot, now)` runs every tick of every bot, before its job thinks;
`act(bot, now)`, after the walk pressed its keys and before the job's hands.
With hundreds of bots each is hundreds of times a tick. Look only at what is
cheap every tick (the body's own fields: health, air, whether it is on fire),
and at the rest every so many ticks (`if (now % 20 != 0) return;`, staggered by
bot if it is heavy). Never a search on the server's thread: `Bots.plan` sends
it to a thread of its own.

A reflex that only watches needs nothing more. One that must do something
takes what it needs, for a while:

- **The body**: `Bots.takeOver(p, this, "fleeing a creeper")`. Its order is
  set aside (its job told `end`, and kept; where it went, whom it followed and
  who is told, kept too) and nothing of it runs: no job, no follow. The walk is
  the reflex's: `Bots.plan(...)` a route and it is walked; keys pressed in
  `act` stand for that tick (a sprint to flee: `p.body.setSprinting(true)`).
  `Bots.giveBack(p, this)` when it is over: the order is taken up again where
  it was. It returns false when another reflex holds the body (first come,
  first served). An order given meanwhile ends the hold and replaces what was
  set aside; the reflex sees `Bots.holding(p) != this` and may take it again.
- **The body, before others**: `Bots.takeOver(p, this, urgency, doing)` takes
  it from a reflex that holds it less urgently, too (the plain `takeOver` holds
  at 0). That one finds `Bots.holding(p) != this`, as when an order ends a hold,
  and lets go of what it did with it (a bow drawn, keys); what was set aside
  stays set aside, and whoever gives the body back gives the order back. A
  reflex still in need takes the body again on its next tick. The ranks there
  are, most urgent first:

  | urgency | reflex | when |
  |---|---|---|
  | 60 | `DiggingOut` | buried: breaking what covers its head |
  | 50 | `Breathing` | out of air under water: coming up |
  | 40 | `Creepers` | a creeper within 10: running |
  | 30 | `Retreating` | badly hurt with something hostile at hand: backing off |
  | 20 | `Defending` | answering an attacker out of reach: shooting back, going for it |
  | 10 | `Creepers` | a creeper 10 to 25 away: shooting it |

  Hold at the rank of what the reflex does now: the same reflex asks again with
  another urgency when that changes (a creeper shot, then run from).
- **The hands**, for a few ticks: `Bots.holdHands(p, this, 32)` (a bite, a
  swing, a bow drawn). The job's hands wait (its `act` is skipped, `Job.hold`
  swaps nothing) while its walk goes on. `Bots.freeHands(p, this)` to let go
  sooner. The reflex puts what it needs in the hand with `Job.wield`. For
  longer than a few seconds, take the body.

A body that uses an item (a bite, a bow drawn) walks at a fifth of its pace and
cannot sprint, as a player's client makes it (`BotPlayer.tick`). An item's use is
the server's own: `body.gameMode.useItem(...)` starts it and the game runs it,
tick by tick, until it is over or what is in the hand changes; nothing on the
server ever lets a use go by itself, so whatever starts one (a bow drawn) stops
it (`body.stopUsingItem()`) on every way out. What there is to build on:

- **Eating**: `Eating.eatBest(p, this)` starts the best food the bot may eat on
  its own (never banned or harmful food), its hands held by the caller for the
  bite; it returns null once it is eating, else why not in words.
  `Eating.eating(p)` says whether a bite is under way. A reflex that eats when
  hungry decides when; this decides what, and does it as a player would.
- **The bow**: `Bow.step(p, target)` one tick of a draw at a target (into the
  hand, aimed where the arrow will meet it, drawn, let go when full and the shot
  is clear: no block in the arc, nobody in the line; that is looked at there, as
  the arrow goes, not on every tick of the decision); `Bow.stop(p)` lets a draw
  down; `Bow.Misses` is the shared memory of targets not worth another arrow.
- **Trash**: `Tossing.tossTrash(p)` tosses its trash but one stack of each block
  it builds with.
- **What is hostile around**: `Threats.around(p, now)`, the mobs hostile to it
  within 25 blocks, walls and all, looked up once a tick for a bot however many
  reflexes ask. Hostile is `Threats.hostile(e, to)`: an enemy, but one that is
  neutral until provoked (an enderman, a zombified piglin, a piglin, a spider in
  the light) only once it is after the bot; hitting a calm zombified piglin
  angers its whole group. Look when `Threats.due(p, now)` says (every 10 ticks,
  each bot on a tick of its own), and at what you already know in between.
  `Threats.noticed(b, e, 4)` is what a player in its place makes out: what it
  sees, and what is right next to it; decide on nothing it does not make out.
  `Threats.takingAim(m, b)` and `Threats.threatens(m, b)` read a mob as a player
  does: its pose (a bow drawn, arms up, a crossbow loaded) and its head turned
  to the bot, not the target its AI keeps.
- **Getting away**: `Retreating.away(p, from, far, doing)` searches a route to
  anywhere `far` blocks from something (a goal of the path finder's,
  `Route.Meta.awayFrom`), walked by the legs; in a closed place with nowhere that
  far it finds none, and the caller asks for less, or is cornered.
- **A search of its own**, apart from the walk (whether a creeper can walk to the
  bot): `Bots.search(level, a, b, world -> ...)` runs on a routes thread over the
  chunks around `a` and `b`, and its answer is a future, looked at on a later
  tick, never waited for. The routes threads are the walks' too: ask only for
  what the reflex would act on, share an answer between bots standing together,
  and ask nothing while `Bots.routesBusy()` (more searches wait than the threads
  get through in a moment; `/tachyon stats` says how many wait).
- **What the others are at**: `Defending.fighting(p)` (hit lately, or answering
  an attacker), `Creepers.fleeing(p)`, `Eating.eating(p)`, `Bots.holding(p)`,
  and `Bots.job(p)`, its job, at it or set aside while a reflex holds the body (a
  kill ordered against the creeper a reflex would shoot: the kill shoots it). A
  reflex that eats, shoots or trades hits by itself asks them first.

**Nothing that sends a bot away, or brings one in, runs inside a tick**: not
from a reflex, not from a hook. `server.execute(...)` does NOT wait there: on
the server's thread it runs at once, in the middle of whatever called it. Use
`Bots.later(...)`, which runs at the end of the tick. (From another thread,
`server.execute` is the way back to the server's.)

## What a bot keeps: `BotData`

`bot.data` is what a bot keeps across leaving and coming back: one JSON file per
bot name in the world save (`<world>/tachyon/bots/<name in lower case>.json`),
in sections by key. Take a section of your own, named for the ability, change
it, and say so:

```java
JsonObject mine = p.data.section("farming");     // made, empty, the first time
mine.addProperty("fields", 3);
p.data.changed();                                // or it is not written
```

and read with `p.data.read("farming")`, which never makes a section (an empty
one, not kept, when it is not there).

- It is read before `joined`, and written after `left`; in between, at most
  every 30 seconds when it changed. A crash loses up to 30 seconds of it.
- **Only on the server's thread**: read, changed, and copied there for the
  writer. Used on another thread (a brain's, a search's), it throws.
- Keep it small: it is written whole each time. A section that grows without
  end (a diary, every place ever seen) needs a limit, or a store of its own.
- Sections `owner` and `settings` are the mod's own.
- A file that is broken is moved aside and the bot starts with nothing; your
  code gets an empty section, as for a bot never seen, and copes. A write that
  fails (a full disk) is kept and tried again every 30 seconds.
- What does not belong in it: what the world already keeps (the inventory, the
  position, the health: the player's own save has them), and what is the same
  for every bot (that is a setting, or `tachyon.properties`).

**What bots share** (every bot of one owner, or all of them: chests seen,
places, farms) goes in a shared store: `BotData.shared(server, "chests")` is
`<world>/tachyon/shared/chests.json`, made of sections and written as a bot's
data is, kept while the server runs. The name is lower case letters, digits,
`_` and `-`: `places-<owner uuid>` for one owner's. A big thing one bot keeps
can have a store of its own too (`chests-<bot name>`), so that its changes do
not rewrite the rest.

**What a bot needs only while it is in the game** (a timer, a cooldown, a
count, a list of reminders) is not data: it goes in the bot's slot for your
ability, `p.slot(Reminders.class, Reminders::new)`, made the first time and
gone when the bot leaves. Never a field added to `Bots.Bot` for one ability.

## Threads

- **The world is the server's thread's.** Commands, tools, the bot hooks, jobs
  and `BotData` all run there, and only there may the world be read or
  changed.
- **What takes long is not.** Route searches run on the routes' threads
  (`Bots.plan` hands them a snapshot of the chunks, taken on the server's
  thread); the model is asked on the brain's threads; files are written on the
  data thread. A result comes back to the server's thread with
  `server.execute(...)`, or is picked up there (a job looks at `p.pending`).
- **A brain turn reads the bot on the server's thread**: its instructions (your
  `rules`), its state (your `state`), the tools it is offered, all at the
  turn's start; and its tools run there through `server.submit(...)`, so a
  handler may read and change the world directly.
- **Nothing that waits (the network, a lock, a big file) runs on the server's
  thread**: a tick has 50 ms for the whole server. Two exceptions, small and
  bounded: a bot's data file (a few hundred bytes) is read there as it comes in,
  as the player's own save is; and at the server's stop the files are written
  there, since nothing runs after it.

## Minecraft's own code

When no NeoForge event fits (the count of sleepers has none), a mixin changes
Minecraft's class: in the package `tachyon.mixin`, listed in the `server` list
of `src/main/resources/tachyon.mixins.json` (the mod is only ever on a dedicated
server). Keep it to one small change, with a comment saying why no event does,
and have it call a `public static` method of the ability, which decides: the
mixin's code runs inside Minecraft's class, in another package. `SleepStatusMixin`
and `Sleeping.counted` are the example. To reach a field Minecraft keeps private,
an `@Accessor` interface (`PlayerListAccess`).

## Before it is done

- `./gradlew build`: it compiles and every test passes, the old ones too.
- A line of the log about one bot goes through `Notices.technical`, and one that
  a player must hear through `Notices.say` or `Notices.tell`: no ability sends
  players a `[tachyon]` line of its own.
- `./gradlew runServer`: try it in a game, with the commands and, when it has
  tools, with a bot spoken to (every word said to a bot is a paid call to a
  model: a few are enough).
- The README: its commands in the table, its settings in the settings table
  (key, label, group, level, who, default, what), and a line on what it does if
  players will ask. `SettingsTest` reads the README's settings table and holds
  it to the settings the mod declares, row for row: a new setting fails it until
  its row is there.
- `/tachyon stats` with many bots, if it adds a reflex or a heavy job: what it
  costs the tick, with nothing to do and with something to do (mobs about at
  night, for a reflex), and how many searches wait for a routes thread.
